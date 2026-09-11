#!/usr/bin/env bash
# The negative control of docs/research/research-oracle.md §1.
#
# Four cells — {jvm, native} x {Ktor's default grace, a grace long enough to see} — repeated, because
# one run per variant is not a measurement. Prints one line per run and a summary per cell.
#
#   samples/oracle/negative-control.sh [repetitions]
#
# It does NOT exit non-zero when a cell fails. A failing control is the expected result and the whole
# point of running it; the verdict is read by a person and written into the research.
set -u

REPEATS="${1:-3}"
WORK_MS="${WORK_MS:-3000}"
CONNECTIONS="${CONNECTIONS:-8}"
LONG_GRACE_MS="${LONG_GRACE_MS:-20000}"

cd "$(dirname "$0")/../.." || exit 3

echo "negative control: ${REPEATS} repetitions per cell, work=${WORK_MS}ms connections=${CONNECTIONS}"
echo

# Three cells per platform, and the third is the one the library's argument rests on.
#
#   default        Ktor's own 1000 ms grace period - what an unconfigured service does
#   long           a grace period long enough for the ordering question to be visible at all
#   long+close     the same, with the stop subscriber closing a resource the in-flight request uses,
#                  which is the ORDINARY thing a service does in ApplicationStopping
#
# Without the third, the control demonstrates nothing about research 1.1: a subscriber that closes
# nothing has no consequence whichever side of the drain it runs on. Twelve green runs said so.
for image in kore-sample:jvm kore-sample:native; do
  for cell in default long long+close kore; do
    case "$cell" in
      default)    subject_args="" ;;
      long)       subject_args="--grace=$LONG_GRACE_MS" ;;
      long+close) subject_args="--grace=$LONG_GRACE_MS,--close-on-stop=true" ;;
      # The treatment arm. It closes the SAME resource `long+close` does — the difference is WHEN,
      # which is the whole claim. An arm that closed nothing would pass by not doing the thing.
      kore)       subject_args="--kore=true" ;;
    esac
    extra=""
    [ "$cell" = "kore" ] && extra=" --pre-drain=2000"

    echo "### $image, $cell"
    for run in $(seq 1 "$REPEATS"); do
      # COMMA-separated subject args: Gradle's --args splits on spaces, so a space here silently
      # hands the second one to the oracle instead of the subject.
      out=$(./gradlew :samples:oracle:oracle --console=plain -q \
        --args="--image=$image --work=$WORK_MS --connections=$CONNECTIONS${extra} --subject-args=$subject_args" 2>&1)
      a1=$(printf '%s\n' "$out" | grep -oE '(PASS|FAIL|INCONCLUSIVE|NOT_APPLICABLE) +A1' | awk '{print $1}')
      a2=$(printf '%s\n' "$out" | grep -oE '(PASS|FAIL|INCONCLUSIVE|NOT_APPLICABLE) +A2' | awk '{print $1}')
      fivexx=$(printf '%s\n' "$out" | grep -oE '[0-9]+ responses in 5xx' | head -1)
      exit_line=$(printf '%s\n' "$out" | grep -oE 'exit [0-9-]+ after [0-9]+ms' | head -1)
      printf '  run %s: A1=%-5s A2=%-5s %-26s %s\n' "$run" "${a1:-?}" "${a2:-?}" "${exit_line:-no exit}" "${fivexx:-}"
    done
    echo
  done
done
