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

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Properties;

import junit.framework.TestCase;

public abstract class IMAPTestCase extends TestCase {
	
	protected void printLongCollection(Collection<Long> uids) {
		for (long l : uids) {
			System.out.print(l + " ");
		}
		System.out.println();
	}

	protected String confValue(String key) {
		InputStream is = getClass().getClassLoader().getResourceAsStream(
				"data/test.properties");
		Properties props = new Properties();
		if (is != null) {
			try {
				props.load(is);
				return props.getProperty(key);
			} catch (IOException e) {
				return null;
			} finally {
				try {
					is.close();
				} catch (IOException e) {
				}
			}
		} else {
			return null;
		}
	}
}
