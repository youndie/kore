# One gate, and CI runs exactly this target.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal state
# of affairs, and then neither is read. So: whatever is not in `make check` is not a gate, and
# whatever is in it runs the same way in both places.
#
# Every script defaults to `docs` in the working directory, so the variables below exist to be
# overridden rather than because anything needs them.

DOCS ?= docs
BACKLOG ?= backlog.md
REPOS ?= ..
PY ?= python3

.PHONY: check gate report fix help

help:
	@echo "make check   - the gate: blocking checks, exactly what CI runs"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. Any of these failing means the documentation is internally inconsistent, which is a
# defect in the documentation rather than a matter of opinion.
#
# NOT here: `docs_check.py --on-main`, which makes `status: draft` an error on the default branch.
# It cannot pass while there is no code and every feature is honestly a draft. It is off with an
# address — B-35 — rather than relaxed, so it turns on by itself when the work that makes it
# passable lands.
gate:
	$(PY) scripts/backlog_index.py --check --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/docs_check.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --check --docs $(DOCS)

# Non-blocking, on purpose.
#
# `bdd_report` counts scenarios; demanding a percentage is meaningless while every scenario is
# target behaviour. `code_anchors` reports most paths as rotten and that is CORRECT here — they are
# where the code will live. The number going down is one way to watch the library arrive; it becomes
# a gate when it reaches zero, and not before.
report:
	$(PY) scripts/bdd_report.py --docs $(DOCS) --repos $(REPOS)
	$(PY) scripts/code_anchors.py --docs $(DOCS) --repos $(REPOS)

fix:
	$(PY) scripts/backlog_index.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --fix --docs $(DOCS)
