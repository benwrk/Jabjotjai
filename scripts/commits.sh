#!/bin/sh
# The one form commit subjects follow, and the release notes generated from them.
#
#   sh scripts/commits.sh check-subject SUBJECT  fail, saying why, unless SUBJECT is in the form
#   sh scripts/commits.sh check RANGE            check every non-merge commit in a revision range
#   sh scripts/commits.sh notes TAG              Markdown release notes for TAG, covering the
#                                                commits since the previous v* tag
#
# A subject is "type(scope): description" or "type: description", with "!" before the colon for
# a change a user has to act on. feat and fix change what the app does and are listed in the
# release notes; the other types change only the project and are counted, not listed. AGENTS.md
# states the rule; decision 0012 records why.
#
# Only POSIX sh, grep and sed features, so it behaves the same under Git for Windows, Linux and
# macOS.

types='feat|fix|build|ci|docs|chore|refactor|test'
subject_form='^('"$types"')(\([a-z0-9][a-z0-9-]*\))?!?: [^ ]'

# Separates the fields of a parsed subject. Not whitespace, so that `read` keeps an empty scope
# as an empty field instead of merging it with its neighbours.
us=$(printf '\037')

check_subject() {
    printf '%s\n' "$1" | grep -E -q -e "$subject_form" && return 0
    printf 'Not in the form type(scope): description, with type one of %s:\n  %s\n' \
        "$(printf '%s' "$types" | sed 's/|/, /g')" "$1" >&2
    return 1
}

# Lists "<hash> <subject>" for every non-merge commit in a range. Merge subjects are written by
# git or GitHub, not by whoever made the change.
list_commits() {
    git log --no-merges --format='%h %s' "$1" || {
        printf 'Could not list the commits in %s.\n' "$1" >&2
        exit 2
    }
}

check_range() {
    commits=$(list_commits "$1") || exit 2
    if [ -z "$commits" ]; then
        printf 'No commits in %s.\n' "$1"
        return 0
    fi
    count=0
    bad=0
    while IFS= read -r line; do
        count=$((count + 1))
        check_subject "${line#* }" 2>/dev/null && continue
        printf 'Not in the form type(scope): description: %s\n' "$line" >&2
        bad=$((bad + 1))
    done <<EOF
$commits
EOF
    if [ "$bad" -gt 0 ]; then
        printf '%s of %s commit subjects in %s are not in the form; see AGENTS.md.\n' \
            "$bad" "$count" "$1" >&2
        exit 1
    fi
    printf 'Every commit subject in %s is in the form (%s checked).\n' "$1" "$count"
}

# Prints one section of the notes, if any parsed subject has a kind matching $2.
section() {
    lines=$(printf '%s\n' "$parsed" | grep -E -e "^($2)$us") || return 0
    printf '## %s\n\n' "$1"
    printf '%s\n' "$lines" | while IFS="$us" read -r kind scope description hash; do
        if [ -n "$scope" ]; then
            printf -- '- **%s**: %s (%s)\n' "$scope" "$description" "$hash"
        else
            printf -- '- %s (%s)\n' "$description" "$hash"
        fi
    done
    printf '\n'
}

notes() {
    tag=$1
    git rev-parse -q --verify "$tag^{commit}" >/dev/null || {
        printf 'No commit for %s.\n' "$tag" >&2
        exit 2
    }
    if previous=$(git describe --tags --abbrev=0 --match 'v[0-9]*' "$tag^" 2>/dev/null); then
        range="$previous..$tag"
        printf 'Changes since %s.\n\n' "$previous"
    else
        range=$tag
        printf 'The first release, so this lists every change.\n\n'
    fi

    commits=$(list_commits "$range") || exit 2
    # Each line becomes kind, scope, description and hash. A subject not in the form keeps its
    # whole text and the kind "other", so it is shown rather than lost.
    parsed=$(printf '%s\n' "$commits" | sed -E \
        -e 's/^([0-9a-f]+) ('"$types"')(\(([a-z0-9][a-z0-9-]*)\))?(!?): (.*)$/\2\5'"$us"'\4'"$us"'\6'"$us"'\1/' \
        -e t \
        -e 's/^([0-9a-f]+) (.*)$/other'"$us$us"'\2'"$us"'\1/')

    shown=$(printf '%s\n' "$parsed" | grep -c -E -e "^([a-z]+!|feat|fix|other)$us")
    project=$(printf '%s\n' "$parsed" | grep -c -E -e "^(build|ci|docs|chore|refactor|test)$us")
    if [ "$shown" -eq 0 ]; then
        printf 'No changes to the app.\n\n'
    fi
    section 'Read first' '[a-z]+!'
    section 'Added' 'feat'
    section 'Fixed' 'fix'
    section 'Not in the usual form' 'other'
    if [ "$project" -eq 1 ]; then
        printf 'Also 1 change to the project itself (build, CI, documentation, tooling), not listed.\n'
    elif [ "$project" -gt 1 ]; then
        printf 'Also %s changes to the project itself (build, CI, documentation, tooling), not listed.\n' \
            "$project"
    fi
}

case "$1" in
    check-subject) check_subject "$2" ;;
    check) check_range "$2" ;;
    notes) notes "$2" ;;
    *)
        printf 'Usage: sh scripts/commits.sh check-subject SUBJECT | check RANGE | notes TAG\n' >&2
        exit 2
        ;;
esac
