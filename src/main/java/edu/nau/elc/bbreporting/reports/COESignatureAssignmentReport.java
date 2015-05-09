package edu.nau.elc.bbreporting.reports;

import edu.nau.elc.bbreporting.domain.Course;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class COESignatureAssignmentReport implements Report {

    private static Logger log = LoggerFactory.getLogger(COESignatureAssignmentReport.class);

    int term;

    public COESignatureAssignmentReport(int termCode) {
        term = termCode;
    }

    /**
     * Run the signature assignment report. It looks for the number of "signature assignments" in each course, as well
     * as the number of enrolled students and the number of rubrics. It's currently used for validating data integrity
     * for other reports that are provided by a commerical tool.
     */
    @Override
    public void runReport(Connection connection, File outputFile) {
        log.info("Running signature assignment report for term: " + term);

        PrintWriter writer;
        try {
            writer = new PrintWriter(outputFile);
            writer.println("Course ID\tCourse Name\tInstructor\tInstructor Email\t# of students enrolled\t# signature assignments\t# unique rubrics");


            String[] coursePrefixes = {"BME", "CCHE", "CTE", "ECI", "EDL", "EDR", "EPS", "ESE", "ETC"};

            List<Course> allCOEcourses = new ArrayList<>();
            for (String prefix : coursePrefixes) {

                allCOEcourses.addAll(singlePrefixQuery(connection, term, prefix));
            }

            log.info("Found all COE courses. Retrieving metrics-per-course and writing to report file.");

            for (Course c : allCOEcourses) {
                int numEnrolled = numberStudentsEnrolled(connection, c);
                int numAssignments = numberSignatureAssessments(connection, c);
                int numRubrics = numberRubrics(connection, c);

                writer.println(c.getCourseID() + "\t" + c.getCourseName() + "\t" + c.getInstructor() + "\t" + c.getInstructorEmail()
                        + "\t" + numEnrolled + "\t" + numAssignments + "\t" + numRubrics);
            }

            writer.flush();
            writer.close();

        } catch (IOException ioe) {
            log.error("Unable to write report file.", ioe);
        }
    }

    private int numberRubrics(Connection sqlConnection, Course course) {
        String queryStr =
                "select DISTINCT\n" +
                        "r.*\n" +
                        "from bblearn.rubric r\n" +
                        "where r.status = 'A'" +
                        "   AND r.deleted_ind = 'N'\n" +
                        "   AND r.course_pk1 = ?";

        int numberRubrics = 0;

        try {
            PreparedStatement statement = sqlConnection.prepareStatement(queryStr);
            statement.setString(1, course.getBbPK1());


            if (statement.execute()) {
                ResultSet resultSet = statement.getResultSet();

                while (resultSet.next()) {
                    numberRubrics++;
                }

                resultSet.close();
                statement.close();
            }
        } catch (SQLException sqe) {
            log.error("Error querying.", sqe);
        }

        return numberRubrics;
    }

    private int numberStudentsEnrolled(Connection sqlConnection, Course course) {
        String queryStr =
                "SELECT DISTINCT\n" +
                        "  cu.*\n" +
                        "FROM\n" +
                        "  bblearn.course_users cu\n" +
                        "WHERE\n" +
                        "  cu.role = 'S'\n" +
                        "  AND cu.available_ind = 'Y'\n" +
                        "  AND cu.row_status = 0\n" +
                        "  AND cu.crsmain_pk1 = ? \n";

        int numberEnrolled = 0;

        try {
            PreparedStatement statement = sqlConnection.prepareStatement(queryStr);
            statement.setString(1, course.getBbPK1());


            if (statement.execute()) {
                ResultSet resultSet = statement.getResultSet();

                while (resultSet.next()) {
                    numberEnrolled++;
                }

                resultSet.close();
                statement.close();
            }
        } catch (SQLException sqe) {
            log.error("Error querying.", sqe);
        }

        return numberEnrolled;
    }

    private int numberSignatureAssessments(Connection sqlConnection, Course course) {
        String queryStr =
                "SELECT\n" +
                        "  cc.title\n" +
                        "from bblearn.course_contents cc\n" +
                        "where\n" +
                        "  cc.cnthndlr_handle = 'resource/x-bb-assignment'\n" +
                        "  and lower(cc.title) like '%signature%assign%'\n" +
                        "  and cc.crsmain_pk1 = ?";

        int numberAssignments = 0;

        try {
            PreparedStatement statement = sqlConnection.prepareStatement(queryStr);
            statement.setString(1, course.getBbPK1());


            if (statement.execute()) {
                ResultSet resultSet = statement.getResultSet();

                while (resultSet.next()) {
                    numberAssignments++;
                }

                resultSet.close();
                statement.close();
            }
        } catch (SQLException sqe) {
            log.error("Error querying.", sqe);
        }

        return numberAssignments;
    }

    private List<Course> singlePrefixQuery(Connection sqlConnection, int term, String prefix) {
        String preparedPrefix = term + "-NAU00-" + prefix + "%";

        String childSectionQuery = "SELECT DISTINCT\n" +
                "  cm.pk1\n" +
                "FROM bblearn.course_main cm, bblearn.course_course cc\n" +
                "where\n" +
                "  cc.crsmain_pk1 = cm.pk1\n" +
                "  AND cm.course_id like ?";

        Set<String> childSectionPK1s = new HashSet<>();

        try {
            PreparedStatement statement = sqlConnection.prepareStatement(childSectionQuery);
            statement.setString(1, preparedPrefix);


            if (statement.execute()) {
                ResultSet resultSet = statement.getResultSet();

                while (resultSet.next()) {
                    childSectionPK1s.add(resultSet.getString("pk1"));
                }

                resultSet.close();
                statement.close();
            }
        } catch (SQLException sqe) {
            log.error("Error querying.", sqe);
        }

        String mainQuery =
                "SELECT DISTINCT\n" +
                        "  cm.pk1,\n" +
                        "  cm.course_id,\n" +
                        "  cm.course_name,\n" +
                        "  u.email,\n" +
                        "  u.user_id,\n" +
                        "  concat(u.firstname, concat(' ', u.lastname)) as \"NAME\"\n" +
                        "FROM bblearn.course_main cm, bblearn.users u, bblearn.course_users pi\n" +
                        "where\n" +
                        "  u.pk1 = pi.users_pk1\n" +
                        "  AND pi.role = 'P'\n" +
                        "  AND pi.crsmain_pk1 = cm.pk1\n" +
                        "  AND cm.course_id like ?\n" +
                        "order by cm.course_id\n";

        List<Course> courses = new LinkedList<>();

        try {
            PreparedStatement statement = sqlConnection.prepareStatement(mainQuery);
            statement.setString(1, preparedPrefix);


            if (statement.execute()) {
                ResultSet resultSet = statement.getResultSet();

                while (resultSet.next()) {
                    String bbPK1 = resultSet.getString("pk1");

                    //ignore any child sections
                    if (childSectionPK1s.contains(bbPK1)) continue;

                    String courseID = resultSet.getString("course_id");
                    String courseName = resultSet.getString("course_name");
                    String instructorName = resultSet.getString("name");
                    String instructorEmail = resultSet.getString("email");
                    String instructorUID = resultSet.getString("user_id");

                    Course current = new Course(
                            bbPK1, courseID, courseName, instructorName, instructorUID, instructorEmail);

                    //check if same course exists with different instructor
                    int dupeIndex = courses.indexOf(current);
                    if (dupeIndex != -1) {
                        Course duplicate = courses.remove(dupeIndex);

                        //merge the instructor fields
                        current.setInstructor(current.getInstructor() + ", " + duplicate.getInstructor());
                        current.setInstructorEmail(current.getInstructorEmail() + ", " + duplicate.getInstructorEmail());
                        current.setInstructorUID(current.getInstructorUID() + ", " + duplicate.getInstructorUID());
                    }

                    courses.add(current);
                }

                resultSet.close();
                statement.close();
            }
        } catch (SQLException sqe) {
            log.error("Error querying.", sqe);
        }

        return courses;
    }
}
