"""MkDocs hook: links from a guide page to files outside docs/guide (the starter README, architecture.md, ...) point at
the file on GitHub, so the same Markdown works on GitHub and on the site."""
import posixpath
import re

REPO = "https://github.com/gkosharovdev/saga-forge/blob/master/"
LINK = re.compile(r"\]\((\.\./[^)#\s]+)(#[^)\s]*)?\)")


def on_page_markdown(markdown, page, **kwargs):
    page_dir = posixpath.dirname("docs/guide/" + page.file.src_uri)

    def rewrite(match):
        target = posixpath.normpath(posixpath.join(page_dir, match.group(1)))
        if target.startswith("docs/guide/"):
            return match.group(0)
        return "](" + REPO + target + (match.group(2) or "") + ")"

    return LINK.sub(rewrite, markdown)
