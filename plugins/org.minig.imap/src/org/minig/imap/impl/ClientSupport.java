/* ***** BEGIN LICENSE BLOCK *****
 * Version: GPL 2.0
 *
 * The contents of this file are subject to the GNU General Public
 * License Version 2 or later (the "GPL").
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Initial Developer of the Original Code is
 *   MiniG.org project members
 *
 * ***** END LICENSE BLOCK ***** */

package org.minig.imap.impl;

import java.io.InputStream;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

import javax.net.ssl.SSLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mina.common.ConnectFuture;
import org.apache.mina.common.IoHandler;
import org.apache.mina.common.IoSession;
import org.apache.mina.transport.socket.nio.SocketConnector;
import org.minig.imap.Envelope;
import org.minig.imap.FlagsList;
import org.minig.imap.IMAPHeaders;
import org.minig.imap.InternalDate;
import org.minig.imap.ListResult;
import org.minig.imap.NameSpaceInfo;
import org.minig.imap.QuotaInfo;
import org.minig.imap.SearchQuery;
import org.minig.imap.command.AppendCommand;
import org.minig.imap.command.CapabilityCommand;
import org.minig.imap.command.CreateCommand;
import org.minig.imap.command.DeleteCommand;
import org.minig.imap.command.ExpungeCommand;
import org.minig.imap.command.ICommand;
import org.minig.imap.command.ListCommand;
import org.minig.imap.command.LoginCommand;
import org.minig.imap.command.LsubCommand;
import org.minig.imap.command.NamespaceCommand;
import org.minig.imap.command.NoopCommand;
import org.minig.imap.command.QuotaRootCommand;
import org.minig.imap.command.RenameCommand;
import org.minig.imap.command.SelectCommand;
import org.minig.imap.command.StartIdleCommand;
import org.minig.imap.command.StopIdleCommand;
import org.minig.imap.command.SubscribeCommand;
import org.minig.imap.command.UIDCopyCommand;
import org.minig.imap.command.UIDFetchBodyStructureCommand;
import org.minig.imap.command.UIDFetchEnvelopeCommand;
import org.minig.imap.command.UIDFetchFlagsCommand;
import org.minig.imap.command.UIDFetchHeadersCommand;
import org.minig.imap.command.UIDFetchInternalDateCommand;
import org.minig.imap.command.UIDFetchMessageCommand;
import org.minig.imap.command.UIDFetchPartCommand;
import org.minig.imap.command.UIDSearchCommand;
import org.minig.imap.command.UIDStoreCommand;
import org.minig.imap.command.UIDThreadCommand;
import org.minig.imap.command.UnSubscribeCommand;
import org.minig.imap.mime.MimeTree;
import org.minig.imap.tls.MinigTLSFilter;

public class ClientSupport {

	private final IoHandler handler;
	private IoSession session;
	private Log logger = LogFactory.getLog(getClass());
	private Semaphore lock;
	private List<IMAPResponse> lastResponses;
	private TagProducer tagsProducer;
	private MinigTLSFilter sslFilter;

	public ClientSupport(IoHandler handler) {
		this.lock = new Semaphore(1);
		this.handler = handler;
		this.tagsProducer = new TagProducer();
		this.lastResponses = Collections
				.synchronizedList(new LinkedList<IMAPResponse>());
	}

	private void lock() {
		try {
			lock.acquire();
		} catch (InterruptedException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException("InterruptedException !!");
		}
	}

	public boolean login(String login, String password,
			SocketConnector connector, SocketAddress address) {
		return this.login(login, password, connector, address, true);
	}

	public boolean login(String login, String password,
			SocketConnector connector, SocketAddress address,
			Boolean activateTLS) {
		if (session != null && session.isConnected()) {
			throw new IllegalStateException(
					"Already connected. Disconnect first.");
		}

		try {
			lock(); // waits for "* OK IMAP4rev1 server...
			ConnectFuture cf = connector.connect(address, handler);
			cf.join();
			if (!cf.isConnected()) {
				lock.release();
				return false;
			}
			session = cf.getSession();
			logger.info("Connection established");
			if (activateTLS) {
				boolean tlsActivated = run(new StartTLSCommand());
				if (tlsActivated) {
					activateSSL(connector);
				} else {
					logger.warn("TLS not supported by IMAP server.");
				}
			}
			logger.info("Sending " + login + " login informations.");
			return run(new LoginCommand(login, password));
		} catch (Exception e) {
			logger.error("login error", e);
			return false;
		}
	}

	private void activateSSL(SocketConnector connector) {
		try {
			sslFilter = new MinigTLSFilter();
			sslFilter.setUseClientMode(true);
			session.getFilterChain().addBefore(
					"org.apache.mina.common.ExecutorThreadModel", "tls",
					sslFilter);
			logger.info("Network traffic with IMAP server will be encrypted. ");
		} catch (Throwable t) {
			logger.error("Error starting ssl", t);
		}
	}

	public void logout() {
		if (session != null) {
			if (sslFilter != null) {
				try {
					sslFilter.stopSSL(session);
				} catch (SSLException e) {
					logger.warn("error stopping ssl", e);
				} catch (IllegalStateException ei) {
					logger.warn("imap connection is already stop");
				}
			}
			session.close().join();
			session = null;
		}
	}

	private <T> T run(ICommand<T> cmd) {
		if (logger.isDebugEnabled()) {
			logger.debug(Integer.toHexString(hashCode()) + " CMD: "
					+ cmd.getClass().getName() + " Permits: "
					+ lock.availablePermits());
		}
		// grab lock, this one should be ok, except on first call
		// where we might wait for cyrus welcome text.
		lock();
		cmd.execute(session, tagsProducer, lock, lastResponses);
		lock(); // this one should wait until this.setResponses is called
		try {
			cmd.responseReceived(lastResponses);
		} catch (Throwable t) {
			logger.error("receiving/parsing imap response to cmd "
					+ cmd.getClass().getSimpleName(), t);
		} finally {
			lock.release();
		}

		return cmd.getReceivedData();
	}

	/**
	 * Called by MINA on message received
	 * 
	 * @param rs
	 */
	public void setResponses(List<IMAPResponse> rs) {
		if (logger.isDebugEnabled()) {
			for (IMAPResponse ir : rs) {
				logger.info("S: " + ir.getPayload());
			}
		}

		synchronized (lastResponses) {
			this.lastResponses.clear();
			this.lastResponses.addAll(rs);
		}
		lock.release();
	}

	public boolean select(String mailbox) {
		return run(new SelectCommand(mailbox));
	}

	public ListResult listSubscribed(String reference, String mailbox) {
		return run(new LsubCommand());
	}

	public ListResult listAll(String reference, String mailbox) {
		return run(new ListCommand());
	}

	public Set<String> capabilities() {
		return run(new CapabilityCommand());
	}

	public boolean noop() {
		return run(new NoopCommand());
	}

	public boolean create(String mailbox) {
		return run(new CreateCommand(mailbox));
	}

	public boolean delete(String mailbox) {
		return run(new DeleteCommand(mailbox));
	}

	public boolean rename(String mailbox, String newMailbox) {
		return run(new RenameCommand(mailbox, newMailbox));
	}

	public boolean subscribe(String mailbox) {
		return run(new SubscribeCommand(mailbox));
	}

	public boolean unsubscribe(String mailbox) {
		return run(new UnSubscribeCommand(mailbox));
	}

	public long append(String mailbox, InputStream in, FlagsList fl) {
		return run(new AppendCommand(mailbox, in, fl));
	}

	public void expunge() {
		run(new ExpungeCommand());
	}

	public QuotaInfo quota(String mailbox) {
		return run(new QuotaRootCommand(mailbox));
	}

	public InputStream uidFetchMessage(long uid) {
		return run(new UIDFetchMessageCommand(uid));
	}

	public Collection<Long> uidSearch(SearchQuery sq) {
		return run(new UIDSearchCommand(sq));
	}

	public Collection<MimeTree> uidFetchBodyStructure(Collection<Long> uid) {
		return run(new UIDFetchBodyStructureCommand(uid));
	}

	public Collection<IMAPHeaders> uidFetchHeaders(Collection<Long> uids,
			String[] headers) {
		return run(new UIDFetchHeadersCommand(uids, headers));
	}

	public Collection<Envelope> uidFetchEnvelope(Collection<Long> uids) {
		return run(new UIDFetchEnvelopeCommand(uids));
	}

	public Collection<FlagsList> uidFetchFlags(Collection<Long> uids) {
		return run(new UIDFetchFlagsCommand(uids));
	}

	public InternalDate[] uidFetchInternalDate(Collection<Long> uids) {
		return run(new UIDFetchInternalDateCommand(uids));
	}

	public Collection<Long> uidCopy(Collection<Long> uids, String destMailbox) {
		return run(new UIDCopyCommand(uids, destMailbox));
	}

	public boolean uidStore(Collection<Long> uids, FlagsList fl, boolean set) {
		return run(new UIDStoreCommand(uids, fl, set));
	}

	public InputStream uidFetchPart(long uid, String address) {
		return run(new UIDFetchPartCommand(uid, address));
	}

	public List<MailThread> uidThreads() {
		// UID THREAD REFERENCES UTF-8 NOT DELETED
		return run(new UIDThreadCommand());
	}

	public NameSpaceInfo namespace() {
		return run(new NamespaceCommand());
	}

	public void startIdle() {
		run(new StartIdleCommand());
	}

	public void stopIdle() {
		run(new StopIdleCommand());
	}
}
