__author__ = 'adam'

import logging
from string import ascii_uppercase

import pandas as pd
from bs4 import BeautifulSoup

log = logging.getLogger('nau_bb_reporting.reports.hardlinks')

lazy_query = """
select
    content_items.course_id,
    cc.main_data
from
    (SELECT DISTINCT
        cm.course_id,
        cc.title,
        cc.pk1
    FROM bblearn.course_main cm, bblearn.course_contents cc
    WHERE
        cc.main_data LIKE '%<a%'
        AND cc.crsmain_pk1 = cm.pk1
        AND cm.course_id LIKE :course_id_like
    ) content_items
  JOIN
bblearn.course_contents cc
ON cc.pk1 = content_items.pk1
"""

greedy_query = """
select DISTINCT
  cm.course_id
from bblearn.course_main cm, bblearn.course_contents cc, bblearn.course_contents_files ccf, bblearn.files f
 WHERE
   NOT REGEXP_LIKE(f.link_name,'(DVD|VT|T)[0-9]{2,5}_(.+).html','i')
   AND f.link_name LIKE '%.htm%'
   AND f.pk1 = ccf.files_pk1
   AND ccf.course_contents_pk1 = cc.pk1
   AND cc.available_ind = 'Y'
   AND cc.cnthndlr_handle = 'resource/x-bb-file'
   AND cc.crsmain_pk1 = cm.pk1
   AND cm.course_id LIKE :course_id_like
"""


def run(term, connection, out_file_path, greedy=False):
    log.info("Running hardlinks report for %s.", term)

    course_ids = set()
    found_course_ids = []
    course_id_patterns = [term + '-NAU00-' + letter + '%' for letter in ascii_uppercase]

    if greedy:
        log.info('Retreiving a list of %s courses with deployed HTML files...', term)
        greedy_cur = connection.cursor()
        greedy_cur.prepare(greedy_query)

        for pattern in course_id_patterns:
            greedy_cur.execute(None, course_id_like=pattern)
            for row in greedy_cur:
                found_course_ids.append((row[0], 'HTML FILE'))

    main_cur = connection.cursor()
    main_cur.prepare(lazy_query)
    log.info('Checking all %s courses for content items with bad links...', term)
    for pattern in course_id_patterns:
        main_cur.execute(None, course_id_like=pattern)
        for row in main_cur:
            course_id = row[0]
            html = row[1]
            found_link = get_first_hardlink(html)
            if course_id not in course_ids and found_link is not None:
                found_course_ids.append((course_id, found_link))
                course_ids.add(course_id)

    log.info('Found all courses, writing to report file.')
    header = ['course id', 'link found']
    df = pd.DataFrame([x for x in found_course_ids], columns=header)
    df.to_excel(out_file_path, sheet_name=term + ' Hardlink courses', encoding='UTF-8', columns=header, index=False)
    log.info('Wrote report to %s', out_file_path)


def get_first_hardlink(html_content):
    soup = BeautifulSoup(html_content)

    urls = [link.get('href') for link in soup.find_all('a')]
    urls.extend([image.get('src') for image in soup.find_all('img')])

    for link in urls:
        if link is None or len(link) == 0:
            continue

        trimmed = link.replace('%20', ' ').strip()
        if len(trimmed) == 0:
            continue

        url = trimmed.replace(' ', '%20')
        url = url.replace('@X@EmbeddedFile.requestUrlStub@X@', 'https://bblearn.nau.edu/')
        url = url.lower()

        if 'iris.nau.edu/owa/redir.aspx' in url:
            return url

        elif 'about:blank' == url:
            continue

        elif 'xid' in url and 'bbcswebdav' in url:
            continue

        elif (url.startswith('http://') or url.startswith('https://')) and 'bblearn' not in url:
            continue

        elif '/images/ci/' in url:
            continue

        elif ('courses' in url or 'webapps' in url or 'bbcswebdav' in url or 'webct' in url or 'vista' in url) \
                and '/institution/' not in url \
                and '/execute/viewdocumentation?' not in url \
                and '/wvms-bb-bblearn' not in url \
                and '/bb-collaborate-bblearn' not in url \
                and '/vtbe-tinymce/tiny_mce' not in url \
                and 'webapps/login' not in url \
                and 'webapps/portal' not in url \
                and 'bbgs-nbc-content-integration-bblearn' not in url \
                and 'bb-selfpeer-bblearn' not in url:
            return url

        elif not url.startswith('https://') and \
                not url.startswith('http://') and \
                not url.startswith('www') and \
                not url.startswith('javascript:') and \
                not url.startswith('mailto:') and \
                not url.startswith('#') and \
                not url.startswith('data:image/') and \
                        'webapps' not in url and \
                        '.com' not in url and \
                        '.net' not in url and \
                        '.edu' not in url and \
                        '.org' not in url and \
                        'http://cdn.slidesharecdn.com/' not in url:
            return url

    return None
