__author__ = 'adam'

import logging
from string import ascii_uppercase

import pandas as pd

from nau_bb_reporting.housekeeping import rows_to_dict_list

log = logging.getLogger('nau_bb_reporting.reports.librarymovies')

query = """
SELECT DISTINCT
  cm.course_id, f.LINK_NAME
FROM bblearn.course_main cm, bblearn.course_contents cc, bblearn.course_contents_files ccf, bblearn.files f
 WHERE
   REGEXP_LIKE(f.link_name,'(DVD|VT|T)[0-9]{2,5}_(.+)\.html','i')
   AND f.pk1 = ccf.files_pk1
   AND ccf.course_contents_pk1 = cc.pk1
   AND cc.crsmain_pk1 = cm.pk1
   AND cm.course_id LIKE :course_id_like
"""


def run(term, connection, out_file_path):
    log.info("Running media file report for %s.", term)

    course_id_patterns = [term + '-NAU00-' + letter + '%' for letter in ascii_uppercase]

    cur = connection.cursor()
    cur.prepare(query)
    log.info('Checking all %s courses for content items linked to e-reserve files...', term)

    df = pd.DataFrame()
    for pattern in course_id_patterns:
        cur.execute(None, course_id_like=pattern)
        results = rows_to_dict_list(cur)
        for result in results:
            course_id = result['COURSE_ID']
            file_name = result['LINK_NAME']

            df = df.append({'course_id': course_id, 'file_name': file_name}, ignore_index=True)

    log.info('Found all %s courses and files, writing to report file.', term)

    df.to_excel(out_file_path, sheet_name=term + ' Hardlink courses', encoding='UTF-8', index=False)
    log.info('Wrote report to %s', out_file_path)
