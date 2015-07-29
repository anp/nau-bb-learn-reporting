"""
This report finds large media files that will result in student downloads
(often over slow connections), and that should probably be streamed.

Checks against a term (or all terms if none provided) for offending files.

Doesn't currently check if files are deployed, because the LMS/CMS frequently
have no idea about links to the Content Collection, so it would be wasted in
many cases.

Also checks for files over a particular size threshold (set in config file)
because they usually suck for students too even though they can't be streamed.
"""

__author__ = 'adam'

import logging

import pandas as pd

log = logging.getLogger('nau_bb_reporting.reports.mediafiles')

query = """
SELECT
  u.FULL_PATH,
  u.FILE_NAME,
  f.FILE_SIZE,
  f.MIME_TYPE
  from BBLEARN_CMS_DOC.XYF_URLS u, BBLEARN_CMS_DOC.XYF_FILES f
where
  ((REGEXP_LIKE(u.FILE_NAME, '^(.+)\.(' || :filename_pattern ||')$', 'i')
    AND f.file_size > 1000000)
   OR (f.file_size > (:mb_threshold * 1000000))
  )
  AND f.MIME_TYPE <> 'trash' AND f.MIME_TYPE IS NOT NULL
  AND u.FILE_ID = f.FILE_ID
  and u.FULL_PATH LIKE '/courses/' || :course_id_like
"""


def run(term, connection, out_file_path, threshold, pattern):
    log.info("Running media file report for %s.", term)

    if term != 'all':
        course_id_pattern = term + '-NAU00-%'
    else:
        course_id_pattern = '%'

    cur = connection.cursor()
    cur.prepare(query)
    log.info('Checking all %s courses for media files and other large files...', term)

    # pretty self explanatory -- the database stores filesize in bytes
    cur.execute(None, course_id_like=course_id_pattern, mb_threshold=threshold, filename_pattern=pattern)
    results = [{'file path': r[0],
                'file name': r[1],
                'size (mb)': round(int(r[2]) / 1000000, 1),
                'mime type': r[3]}
               for r in cur.fetchall()]

    log.info('Found all %s courses and files, writing to report file.', term)

    df = pd.DataFrame(results)
    df.to_excel(out_file_path, encoding='UTF-8', index=False)
    log.info('Wrote report to %s', out_file_path)
