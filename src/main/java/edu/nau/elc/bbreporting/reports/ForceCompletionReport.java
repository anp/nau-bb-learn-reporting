package edu.nau.elc.bbreporting.reports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ForceCompletionReport implements Report {

    private static Logger log = LoggerFactory.getLogger(ForceCompletionReport.class);

    int term;

    public ForceCompletionReport(int termCode) {
        term = termCode;
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
            writer.println("PI_UserID\tPI_FirstName\tPI_LastName\tPI_Email\tCourseID\tCourseName\tTestName\tPathToTest");

            log.info("Querying database for all " + term + " tests with Force Completion...");

            // start querying based on starting letter of username
            for (char c = 'a'; c <= 'z'; c++) {
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
     * @param sqlConnection       Supplied by the ReportRunner originally.
     * @param writer              Writer is already initialized for the report file.
     * @param firstUsernameLetter First letter of the instructor's username. Allows splitting the query into 26 parts.
     */
    private void singleLetterQuery(Connection sqlConnection, PrintWriter writer, char firstUsernameLetter) {
        String queryStr = "SELECT DISTINCT \n" +
                "  u.user_id,\n" +
                "  u.firstname,\n" +
                "  u.lastname,\n" +
                "  u.email,\n" +
                "  cm.course_name,\n" +
                "  cm.course_id,\n" +
                "  cc.title,\n" +
                "  cc.pk1\n" +
                "FROM bblearn.course_assessment ca, bblearn.course_main cm, bblearn.users u, bblearn.course_users cu,\n" +
                "  bblearn.course_contents cc, bblearn.link l\n" +
                "WHERE " +
                // we're looking for tests AND surveys, thus the like keyword
                "      cc.cnthndlr_handle LIKE 'resource/x-bb-asmt-%-link'" +
                "	   AND cc.pk1 = l.course_contents_pk1\n" +
                // this connects the content item to the assessment itself
                // force completion can only be turned on once test is deployed as content
                "	   AND l.link_source_table = 'COURSE_ASSESSMENT'" +
                "      AND l.link_source_pk1 = ca.pk1\n" +

                // this is the thing we're looking for...
                "      AND ca.force_completion_ind = 'Y'\n" +
                "      AND ca.crsmain_pk1 = cm.pk1\n" +

                "      AND cm.course_id LIKE ?\n" +
                "      AND cu.crsmain_pk1 = cm.pk1\n" +
                "      AND cu.role = 'P'\n" +
                "	   AND u.pk1 = cu.users_pk1\n" +
                // here we restrict to the first letter (see prepared statement)
                "	   AND u.user_id LIKE ? \n" +
                "ORDER BY u.user_id ASC";

        try {
            PreparedStatement statement = sqlConnection.prepareStatement(queryStr);

            // the term code goes at the beginning of the course ID, e.g. 1151-NAU00-ENG-105-SEC01-5555.NAU-PSSIS
            statement.setString(1, term + "-NAU00-%");
            statement.setString(2, firstUsernameLetter + "%");


            if (statement.execute()) {
                // not strictly necessary but it runs a while so status updates are good
                log.info("Force Completion results for usernames starting with \'" + firstUsernameLetter
                        + "\' returned, querying paths to tests and writing to report file...");

                ResultSet resultSet = statement.getResultSet();

                while (resultSet.next()) {
                    // get the info about the course and test found, then we'll find where it's located in the course
                    String primaryInstructorFirstName = resultSet.getString("FIRSTNAME");
                    String primaryInstructorLastName = resultSet.getString("LASTNAME");
                    String primaryInstructorEmail = resultSet.getString("EMAIL");
                    String primaryInstructorUID = resultSet.getString("USER_ID");
                    String courseID = resultSet.getString("COURSE_ID");
                    String courseName = resultSet.getString("COURSE_NAME");
                    String testTitle = resultSet.getString("TITLE");

                    String content_pk1 = resultSet.getString("PK1");

                    // ah, the joy of hierarchical SQL queries. wait, did I say joy?
                    String pathQuery = "SELECT\n" +
                            "  PATH\n" +
                            "FROM (\n" +
                            "  SELECT\n" +
                            "    SYS_CONNECT_BY_PATH(cc.title, '><') \"PATH\",\n" +
                            "    CONNECT_BY_ISLEAF          \"LEAF\"\n" +
                            "  FROM bblearn.course_contents cc\n" +
                            "  START WITH cc.pk1 = ? \n" +
                            "  CONNECT BY PRIOR cc.parent_pk1 = cc.pk1\n" +
                            ")\n" +
                            // we only want the path that is at the "end" which,
                            // even though it's at the top of the hierarchy,
                            // is the leaf as far as oracle is concerned
                            "WHERE LEAF = 1";

                    PreparedStatement pathStatement = sqlConnection.prepareStatement(pathQuery);
                    pathStatement.setString(1, content_pk1);

                    pathStatement.execute();
                    ResultSet pathResults = pathStatement.getResultSet();
                    pathResults.next();

                    // because oracle sees the "top" as the "leaf" in this query, we need to reverse the sequence
                    String reversePath = pathResults.getString("PATH");

                    // this is an arbitrary separator that's unlikely to be in a real path, see it above in the query
                    String[] pathElements = reversePath.split("><");

                    StringBuilder build = new StringBuilder();

                    for (int i = pathElements.length - 1; i > 1; i--) {
                        // Bb Learn also stores these two titles as bizarre key strings:

                        String pathElement = pathElements[i]
                                .replace("VISTA_ORGANIZER_PAGES.label", "Course Content")
                                .replace("COURSE_DEFAULT.Content.CONTENT_LINK.label", "Content");

                        // so we'll swap them out for what displays to the user and then add them back onto the path
                        build.append(pathElement);

                        if (i > 2)
                            build.append(" > ");
                    }
                    String path = build.toString();

                    // write the line to the report file
                    // by all measures of sanity, this should really be an object which is stored in a data structure
                    // and then serialized into the report when all is said and done
                    // but this is a hack and it works.
                    writer.println(primaryInstructorUID + "\t" + primaryInstructorFirstName + "\t" + primaryInstructorLastName
                            + "\t" + primaryInstructorEmail + "\t" + courseID + "\t" + courseName + "\t" + testTitle + "\t" + path);
                    pathResults.close();
                    pathStatement.close();
                }

                resultSet.close();
                statement.close();
            }
        } catch (SQLException sqe) {
            log.error("Error querying.", sqe);
        }
    }
}
