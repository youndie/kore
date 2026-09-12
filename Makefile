# Two gates, and CI runs exactly these targets.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal state
# of affairs, and then neither is read. So: whatever is not behind one of these targets is not a
# gate, and whatever is runs the same way in both places.
#
# `check` reads the documents — python, seconds, no JDK. `build` compiles and tests — a toolchain and
# minutes. They are separate targets because a contributor editing a document should not need the
# second, and one target that needed both would be one nobody ran.
#
# This header used to say "whatever is not in `make check` is not a gate", which was **false**: CI's
# `build` job blocks a pull request and had no named target at all, so the one gate a contributor
# could not run locally was the slow one they would find out about from a red pull request (B-02).
#
# Every script defaults to `docs` in the working directory, so the variables below exist to be
# overridden rather than because anything needs them.

DOCS ?= docs
BACKLOG ?= backlog.md
REPOS ?= ..
PY ?= python3
GRADLE ?= ./gradlew
# CI passes `--no-daemon`; a laptop wants the daemon. The flags are the only difference between the
# two, which is the point — the command itself is one string in one place.
GRADLEFLAGS ?=

.PHONY: check gate report fix build help

help:
	@echo "make check   - the documentation gate: blocking, exactly what CI's check job runs"
	@echo "make build   - the code gate: blocking, exactly what CI's build job runs"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. Any of these failing means the documentation is internally inconsistent, which is a
# defect in the documentation rather than a matter of opinion.
#
# NOT here: `docs_check.py --on-main`, which makes `status: draft` an error on the default branch.
# It is **on in CI** since B-35, and it stays out of this target because it is branch-specific rather
# than optional: a draft is legal in a pull request, where it means "this branch will make it true",
# and a defect the moment it merges. Running it locally would fail a contributor for writing the
# draft the process asks them to write.
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

# The code gate. One `build` for every target the project declares; what that does and does not
# cover is in CLAUDE.md rather than assumed here.
build:
	$(GRADLE) build $(GRADLEFLAGS)

fix:
	$(PY) scripts/backlog_index.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --fix --docs $(DOCS)
