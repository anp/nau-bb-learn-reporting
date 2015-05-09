package edu.nau.elc.bbreporting.reports;

import java.io.File;
import java.sql.Connection;

public interface Report {

    void runReport(Connection connection, File outputFile);

}
