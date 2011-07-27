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

package org.minig.imap;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import org.minig.imap.sieve.SieveScript;

public class SieveClientTests extends SieveTestCase {

	private SieveClient sc;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		sc = new SieveClient(confValue("imap"), 2000, confValue("login"),
				confValue("password"));
		sc.login();
	}

	public void testEmpty() {
		// empty test to validate setup & teardown
	}

	public void testPutscript() {
		String name = "test." + System.currentTimeMillis() + ".sieve";
		String content = "" // test script
				+ "require [ \"fileinto\", \"imapflags\", "
				// +"\"body\", " // cyrus 2.3 extensions ?!
				+ "\"vacation\" ];\n" // extensions
				// +"if body :text :contains \"viagra\"{\n   discard;\n}\n"
				+ "if size :over 500K {\n   setflag \"\\\\Flagged\";\n}\n"
				+ "fileinto \"INBOX\";\n";
		System.out.println("content:\n" + content);
		InputStream contentStream = new ByteArrayInputStream(content.getBytes());
		boolean ret = sc.putscript(name, contentStream);
		assertTrue(ret);
	}

	public void testListscripts() {
		sc.listscripts();
	}

	public void testListscriptsBenchmark() {
		int COUNT = 1000;

		sc.logout();
		long time = System.currentTimeMillis();

		sc.login();
		int old = sc.listscripts().size();
		sc.logout();

		
		for (int i = 0; i < COUNT; i++) {
			boolean loginOk = sc.login();
			assertTrue(loginOk);
			int cur = sc.listscripts().size();
			sc.logout();
			assertEquals(old, cur);
			old = cur;
		}

		time = System.currentTimeMillis() - time;
		System.out.println(COUNT + " listscripts done in " + time
				+ "ms. Performing at " + ((1000.0 * COUNT) / time) + "/s.");
	}

	public void testListAndDeleteAll() {
		List<SieveScript> scripts = sc.listscripts();
		System.err.println("needs to delete " + scripts.size() + " scripts.");
		for (SieveScript ss : scripts) {
			sc.deletescript(ss.getName());
		}
		scripts = sc.listscripts();
		assertTrue(scripts.size() == 0);
	}

	@Override
	protected void tearDown() throws Exception {
		sc.logout();
		sc = null;
		super.tearDown();
	}

}
