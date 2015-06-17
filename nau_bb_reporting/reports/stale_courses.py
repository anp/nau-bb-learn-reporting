__author__ = 'adam'

import logging

import pandas as pd

log = logging.getLogger('nau_bb_reporting.reports.stale_courses')

# this is NAU-specific magic, and is based on how our course IDs are named
# if adapting to your institution, replace with something that checks whether it's in a for-credit term
# or something comparable
query = """
SELECT *
FROM
  (SELECT last_access_date, cm.dtcreated, cm.course_id, cm.course_name
    FROM bblearn.course_users cu, bblearn.course_main cm
    WHERE cu.crsmain_pk1 = cm.pk1
          AND cm.course_id NOT LIKE '%.NAU-PSSIS'
          AND cm.course_id NOT LIKE '%.CONTENT'
          AND cu.last_access_date = (SELECT max(last_access_date)
            FROM bblearn.course_users cu2 WHERE cu2.crsmain_pk1 = cm.pk1)
    ORDER BY last_access_date DESC
  )
WHERE last_access_date <= trunc(sysdate) - 365
  AND LAST_ACCESS_DATE > DTCREATED
order by COURSE_ID ASC
"""

result_columns = ['last access', 'date created', 'course id', 'course name']


def run(connection, out_file_path):
    log.info("Running stale courses report, ignoring any term provided.")
    cur = connection.cursor()

    log.info("Executing stale courses query...")
    cur.execute(query)

    log.info("Fetching results...")
    df = pd.DataFrame(cur.fetchall(), columns=result_columns)

    log.info("Writing to excel file...")
    df.to_excel(out_file_path, sheet_name='stale non-credit courses', index=False, encoding='UTF-8', na_rep='N/A')

    log.info("Done with stale courses report!")
