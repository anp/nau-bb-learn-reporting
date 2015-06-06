package edu.nau.elc.bbreporting.reports;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;

public class HardlinkCoursesReport implements Report {

	private static Logger log = LogManager.getLogger(HardlinkCoursesReport.class);

	int term;
	boolean aggressive;

	public HardlinkCoursesReport(int termCode, boolean aggressive) {
		term = termCode;
		this.aggressive = aggressive;
	}

	/**
	 * Runs the force completion report, but it's split up into the 26 potential starting letters of a username.
	 * The database server does not appreciate trying to get all 5000+ tests from a term at the same time.
	 *
	 * @param connection SQL connection provided by ReportRunner.
	 * @param outputFile File to write to.
	 */
	@Override
	public void runReport(Connection connection, File outputFile) {
		PrintWriter writer;
		try {
			writer = new PrintWriter(outputFile);

			// write the TSV header
			writer.println("Course_ID");

			log.info("Querying database for all " + term + " courses that should be checked for hardlinks...");

			// start querying based on starting letter of course prefix
			for (char c = 'A'; c <= 'Z'; c++) {
				singleLetterQuery(connection, writer, c);
			}

			log.info("Done writing Force Completion report file.");

			writer.flush();
			writer.close();

		} catch (IOException ioe) {
			log.error("Unable to write report file.", ioe);
		}
	}

	/**
	 * Query and write the report criteria for a single starting letter of the instructor's username.
	 *
	 * @param sqlConnection     Supplied by the ReportRunner originally.
	 * @param writer            Writer is already initialized for the report file.
	 * @param firstPrefixLetter First letter of the course prefix. Allows splitting the query into 26 parts.
	 */
	private void singleLetterQuery(Connection sqlConnection, PrintWriter writer, char firstPrefixLetter) {
		String contentLinksQuery = "select\n" +
				"  content_items.course_id,\n" +
				"  cc.main_data\n" +
				"from (SELECT DISTINCT\n" +
				"   cm.course_id,\n" +
				"   cc.title,\n" +
				"   cc.pk1\n" +
				" FROM bblearn.course_main cm, bblearn.course_contents cc\n" +
				" WHERE\n" +
				"   cc.main_data LIKE '%<a%'\n" +
				"   AND cc.crsmain_pk1 = cm.pk1\n" +
				"   AND cm.course_id LIKE ? \n" +
				") content_items\n" +
				"  JOIN\n" +
				"bblearn.course_contents cc\n" +
				"ON cc.pk1 = content_items.pk1";

		String htmlFilesQuery = "select DISTINCT\n" +
				"  cm.course_id\n" +
				"from bblearn.course_main cm, bblearn.course_contents cc, bblearn.course_contents_files ccf, bblearn.files f\n" +
				" WHERE\n" +
				"   NOT REGEXP_LIKE(f.link_name,'(DVD|VT|T)[0-9]{2,5}_(.+).html','i')\n" +
				"   AND f.link_name LIKE '%.htm%'\n" +
				"   AND f.pk1 = ccf.files_pk1\n" +
				"   AND ccf.course_contents_pk1 = cc.pk1\n" +
				"   AND cc.cnthndlr_handle = 'resource/x-bb-file'\n" +
				"   AND cc.crsmain_pk1 = cm.pk1\n" +
				"   AND cm.course_id LIKE ?";

		try {
			PreparedStatement contentStatement = sqlConnection.prepareStatement(contentLinksQuery);

			contentStatement.setString(1, term + "-NAU00-" + firstPrefixLetter + "%");

			Set<String> hardlinkCourses = new TreeSet<>();


			if (contentStatement.execute()) {
				// not strictly necessary but it runs a while so status updates are good
				log.info("Potential hardlink results for course prefixes starting with \'" + firstPrefixLetter
						+ "\' returned, checking links for hardlinks...");

				ResultSet resultSet = contentStatement.getResultSet();

				while (resultSet.next()) {
					// get the info about the course found, then we'll check if they need to be checked in greater depth
					String courseID = resultSet.getString("COURSE_ID");
					String mainText = resultSet.getString("MAIN_DATA");

					if (hasHardLinks(mainText)) hardlinkCourses.add(courseID);
				}

				resultSet.close();
			}
			contentStatement.close();

			if (aggressive) {
				PreparedStatement htmlFileStatement = sqlConnection.prepareStatement(htmlFilesQuery);

				// the term code goes at the beginning of the course ID, e.g. 1151-NAU00-ENG-105-SEC01-5555.NAU-PSSIS
				htmlFileStatement.setString(1, term + "-NAU00-" + firstPrefixLetter + "%");

				if (htmlFileStatement.execute()) {
					// not strictly necessary but it runs a while so status updates are good
					log.info("Deployed HTML file results for course prefixes starting with \'" + firstPrefixLetter
							+ "\' returned...");

					ResultSet resultSet = htmlFileStatement.getResultSet();

					while (resultSet.next()) {
						hardlinkCourses.add(resultSet.getString("COURSE_ID"));
					}

					resultSet.close();
				}
				htmlFileStatement.close();
			}
			for (String courseID : hardlinkCourses) {
				writer.println(courseID);
			}
		} catch (SQLException sqe) {
			log.error("Error querying.", sqe);
		}
	}

	private boolean hasHardLinks(String htmlContent) {
		Document doc = Jsoup.parse(htmlContent);

		for (Element e : doc.getElementsByTag("a")) {
			String url = e.attr("href");
			if (isHardLink(url)) return true;
		}

		for (Element e : doc.getElementsByTag("img")) {
			String url = e.attr("src");
			if (isHardLink(url)) return true;
		}

		return false;
	}

	private boolean isHardLink(String url) {
		boolean isHardLink;
		url = url.replaceAll("@X@.*?@X@", "https://bblearn.nau.edu/");

		if (url.startsWith("%20")) url = url.replaceFirst("%20", "");

		if (url.contains("xid") && url.contains("bbcswebdav")) {
			isHardLink = false;


		} else if ((url.startsWith("http://") || url.startsWith("https://") || url.startsWith("www"))
				&& !url.contains("bblearn")) {
			isHardLink = false;

		} else if (url.startsWith("/images/ci/")) {
			isHardLink = false;


		} else if (!url.startsWith("https://") && !url.startsWith("http://")
				&& !url.startsWith("javascript:")
				&& !url.startsWith("mailto:") && !url.startsWith("#")) {
			isHardLink = aggressive;


		} else isHardLink = (url.contains("courses") || url.contains("webapp") || url.contains("bbcswebdav"))
				&& (!url.contains("execute/viewDocumentation?")
				&& !url.contains("wvms-bb-BBLEARN")
				&& !url.contains("bb-collaborate-BBLEARN")
				&& !url.contains("/xid-")
				&& !url.contains("webapps/vtbe-tinymce/tiny_mce")
				&& !url.contains("webapps/login"));
		return isHardLink;
	}
}
