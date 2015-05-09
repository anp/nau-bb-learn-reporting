package edu.nau.elc.bbreporting.reports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class StaleCoursesReport implements Report {

    private static Logger log = LoggerFactory.getLogger(StaleCoursesReport.class);

    public StaleCoursesReport() {
    }

    /**
     * Runs the stale non-credit course report.
     *
     * @param sqlConnection Provided by ReportRunner to prevent spinning a new connection up every time.
     * @param outputFile    Provided by ReportRunner because the report shouldn't care about the specific filename.
     */
    @Override
    public void runReport(Connection sqlConnection, File outputFile) {
        String queryStr = "SELECT *\n" +
                "FROM \n" +
                "  (SELECT last_access_date, cm.course_id, cm.course_name\n" +
                "    FROM bblearn.course_users cu, bblearn.course_main cm\n" +
                "    WHERE cu.crsmain_pk1 = cm.pk1\n" +

                // this is NAU-specific magic, and is based on how our course IDs are named
                // if adapting to your institution, replace with something that checks whether it's in a for-credit term
                // or something comparable
                "          AND cm.course_id NOT LIKE '%.NAU-PSSIS'\n" +
                "          AND cm.course_id NOT LIKE '%.CONTENT'\n" +

                "          AND cu.last_access_date = (SELECT max(last_access_date)\n" +
                "            FROM bblearn.course_users cu2 WHERE cu2.crsmain_pk1 = cm.pk1)\n" +
                "    ORDER BY last_access_date DESC\n" +
                "  )\n" +

                //the length of time considered stale should be moved to a configuration option
                "WHERE last_access_date <= trunc(sysdate) - 365\n" +
                "order by last_access_date ASC";

        try {
            Statement statement = sqlConnection.createStatement();

            log.info("Querying database for all \"stale\" courses...");

            if (statement.execute(queryStr)) {

                // after executing the query, we just need to write the basic course info to a file

                log.info("Stale course results returned, writing to report file...");

                PrintWriter writer = new PrintWriter(new FileOutputStream(outputFile));
                writer.println("CourseID\tCourseName\tLastAccessed");
                ResultSet resultSet = statement.getResultSet();
                while (resultSet.next()) {
                    String courseID = resultSet.getString("COURSE_ID");
                    String courseName = resultSet.getString("COURSE_NAME");

                    Timestamp timestamp = resultSet.getTimestamp("LAST_ACCESS_DATE");
                    LocalDateTime datetime = timestamp.toLocalDateTime();

                    writer.println(courseID + "\t" + courseName + "\t" + datetime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                }

                resultSet.close();
                statement.close();

                writer.flush();
                writer.close();
                log.info("Done writing stale course report file.");
            }
        } catch (SQLException sqe) {
            log.error("SQL error when querying for stale courses.", sqe);
        } catch (IOException ioe) {
            log.error("I/O error when writing stale course report file.", ioe);
        }
    }
}
