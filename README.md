# nau-bb-learn-reporting

#####Note: if you're reading this text editor or as plaintext, there is an HTML version: README.html. Try opening that in a browser.

A basic tool for generating reproducible reports for Blackboard Learn 9.1. Relies on access to a Managed Hosting OpenDB account.

This tool was created to solve a very specific set of niche problems at a particular university. It's a very light framework for connecting to Bb Learn's Oracle database and running pre-defined reports through an optional SSH proxy.

###Setup

To install:

1. Install Python >=3.4.
2. Install the Oracle Instant Client: http://www.oracle.com/technetwork/database/features/instant-client/index-097480.html
3. Clone the repository.
4. Inside the repo folder, run `python3 setup.py install` (or use whatever python3 alias your system has).

###Running Reports

A configuration file _must_ be specified. See `reporting_config.ini.example` for an example.

```bash
usage: reporter.py [-h] --config FILE [--term TERMCODE] [--greedy]
                   {force-completion,hardlinks,stale-courses,mediafiles,orphaned-internal}

Bb Learn reporting tool from Northern Arizona University.

positional arguments:
  {force-completion,hardlinks,stale-courses,mediafiles,orphaned-internal}
                        Which report to run. Make sure to use the --term flag
                        where needed.

optional arguments:
  -h, --help            show this help message and exit
  --config FILE         Path to ini file.
  --term TERMCODE       4-digit term code to run report against. Needed for
                        almost all reports.
  --greedy              Increases level of detail for the hardlinks report.
                        Ignored otherwise.
```

###Available Reports

These reports are run more regularly or are more complex, and are run through the CLI tool:

* Stale "non-credit" courses report
  * To check for manually-created courses (i.e. not in the Student Information Service) courses that haven't had any activity in over a year.
* Force Completion report
  * If you use or administer Bb Learn, you know the nightmare that is Force Completion. This checks for all courses in a term that have tests with Force Completion enabled. Then, it goes and finds the exact location in the course so that the instructor can be easily notified to turn it off.
* Hardlink report
  * Reports on course IDs in a given term that have problem links in their HTML content. Problem links are defined as links directly to a specific course that is using the content system (i.e. 'bbcswebdav/COURSE_ID/file.txt'). These course IDs can then be examined more closely using https://github.com/dikaiosune/nau-bb-learn-link-analyzer.
* Media Files report
  * Finds all files in content collections which is either over a size threshold (set in configuration) or has a file extension matching the list set in configuration. The example configuration has a list of file formats that should capture most large media files.
* Orphaned Internal Content
  * Sometimes when a course is deleted, the folder from /internal/courses/COURSE_ID isn't deleted. This contains student attachments and other files that aren't a part of course content. To free up space these should be deleted if they've been orphaned by the deletion process.

In `one-off-queries.txt` there are also a number of queries that target particular questions, and are usually only run once or a few times:

* Finding courses where NBC Learn was in use before our license expired.
* Finding courses that have linked to or embedded pages from a soon-to-be-deprecated media server.
* Finding courses that have HTML files containing embedded players from a soon-to-be-deprecated e-Reserves system.

###Examples

Some example commands (assuming they are run from the root of the repository where the example config was convered to an actual ini):

Force Completion doesn't require a term flag, but will probably take a long time (and be a huge file) if it runs w/o a term:
```bash
python3 nau_bb_reporting/reporter.py --config reporting_config.ini --term 1157 force-completion
python3 nau_bb_reporting/reporter.py --config reporting_config.ini force-completion
```

Hardlinks reports require a term because the report is rather bandwidth intensive:
```bash
python3 nau_bb_reporting/reporter.py --config reporting_config.ini --term 1157 hardlinks
```

Stale courses reports will ignore the termcode, so don't bother:
```bash
python3 nau_bb_reporting/reporter.py --config reporting_config.ini stale-courses
```

Media files reporting does not require a term, but is rather long-running without it:
```bash
python3 nau_bb_reporting/reporter.py --config reporting_config.ini --term 1157 mediafiles
python3 nau_bb_reporting/reporter.py --config reporting_config.ini mediafiles
```

Orphaned internal content ignores termcodes, so don't bother:
```bash
python3 nau_bb_reporting/reporter.py --config reporting_config.ini orphaned-internal
```

###Changing Reports, Adding New Reports

Read through the source. The comments in `reporter.py` have some basic steps to follow for adding new reports.

###Rendering the HTML README

If you change the README.md, the HTML version will be out of sync. To resolve this, first install [grip](https://github.com/joeyespo/grip):

```bash
sudo pip install grip
```

Then, in the base repo folder, re-export the README:

```bash
grip --export
```
