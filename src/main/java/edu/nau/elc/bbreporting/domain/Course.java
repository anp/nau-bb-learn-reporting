package edu.nau.elc.bbreporting.domain;

/**
 * POJO which is used for storing information about a course. Only implemented in the COESignatureAssignmentReport so far.
 */
public class Course {
    private String bbPK1;
    private String courseID;
    private String courseName;
    private String instructor;
    private String instructorUID;
    private String instructorEmail;

    public Course(String bbPK1, String courseID, String courseName, String instructor, String instructorUID, String instructorEmail) {
        this.bbPK1 = bbPK1;
        this.courseID = courseID;
        this.courseName = courseName;
        this.instructor = instructor;
        this.instructorUID = instructorUID;
        this.instructorEmail = instructorEmail;
    }

    public Course(String bbPK1, String courseID, String courseName) {
        this.bbPK1 = bbPK1;
        this.courseID = courseID;
        this.courseName = courseName;

        instructor = "";
        instructorUID = "";
        instructorEmail = "";
    }

    public String getBbPK1() {
        return bbPK1;
    }

    public void setBbPK1(String bbPK1) {
        this.bbPK1 = bbPK1;
    }

    public String getCourseID() {
        return courseID;
    }

    public void setCourseID(String courseID) {
        this.courseID = courseID;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getInstructor() {
        return instructor;
    }

    public void setInstructor(String instructor) {
        this.instructor = instructor;
    }

    public String getInstructorUID() {
        return instructorUID;
    }

    public void setInstructorUID(String instructorUID) {
        this.instructorUID = instructorUID;
    }

    public String getInstructorEmail() {
        return instructorEmail;
    }

    public void setInstructorEmail(String instructorEmail) {
        this.instructorEmail = instructorEmail;
    }

    // this was auto-generated
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Course course = (Course) o;

        return !bbPK1.equals(course.bbPK1) ? false : courseID.equals(course.courseID);

    }

    // this was auto-generated
    @Override
    public int hashCode() {
        int result = bbPK1.hashCode();
        result = 31 * result + courseID.hashCode();
        return result;
    }

    // this was auto-generated
    @Override
    public String toString() {
        return "Course{" +
                "bbPK1='" + bbPK1 + '\'' +
                ", courseID='" + courseID + '\'' +
                ", courseName='" + courseName + '\'' +
                ", instructor='" + instructor + '\'' +
                ", instructorUID='" + instructorUID + '\'' +
                ", instructorEmail='" + instructorEmail + '\'' +
                '}';
    }
}
