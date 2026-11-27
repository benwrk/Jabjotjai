#!/bin/sh
# Writes the third-party notices for the app's release build to standard output:
#
#   sh scripts/third-party-notices.sh > THIRD_PARTY_NOTICES.md
#
# Lists every library in the release runtime classpath with the licence its POM declares, any
# NOTICE file its archive carries, and the full licence texts from docs/licenses/. Run it again
# whenever a dependency changes; CI fails while the committed file is out of date. A licence it
# does not recognise stops it with an error, to be added here with its text, rather than being
# guessed. Decision 0015 records why.
#
# It runs Gradle, so the signing properties must be set (decision 0010). Only POSIX sh, awk, sed,
# grep and unzip, so it behaves the same under Git for Windows, Linux and macOS.
set -e
cd "$(dirname "$0")/.."

cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
licences=docs/licenses
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# group:artifact:version of every resolved library, skipping version constraints and requests
# that were not resolved.
./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath |
    awk '/--- / {
        sub(/^.*--- /, "")
        if ($0 ~ /\((c|n)\)$/) next
        split($1, part, ":")
        version = part[3]
        if ($2 == "->") version = $3
        if (version != "") print part[1] ":" part[2] ":" version
    }' | sort -u > "$work/coordinates"
[ -s "$work/coordinates" ] || { echo "No dependencies were listed." >&2; exit 1; }

pom_of() { # pom_of group artifact version
    ls "$cache/$1/$2/$3"/*/"$2-$3.pom" 2>/dev/null | head -n 1
}

# The licence names a POM declares, following its parent POMs, one per line.
licence_names() { # licence_names group artifact version
    pom=$(pom_of "$1" "$2" "$3")
    [ -n "$pom" ] || { echo "No POM in the Gradle cache for $1:$2:$3." >&2; exit 1; }
    flat=$(tr -d '\r\n' < "$pom")
    names=$(printf '%s' "$flat" | grep -o '<licenses>.*</licenses>' | grep -o '<name>[^<]*</name>' |
        sed 's/<[^>]*>//g' || true)
    if [ -n "$names" ]; then
        printf '%s\n' "$names"
        return
    fi
    parent=$(printf '%s' "$flat" | grep -o '<parent>.*</parent>' || true)
    [ -n "$parent" ] || return 0
    pg=$(printf '%s' "$parent" | sed 's/.*<groupId>\([^<]*\)<.*/\1/')
    pa=$(printf '%s' "$parent" | sed 's/.*<artifactId>\([^<]*\)<.*/\1/')
    pv=$(printf '%s' "$parent" | sed 's/.*<version>\([^<]*\)<.*/\1/')
    licence_names "$pg" "$pa" "$pv"
}

# Sorts each library under one licence key: apache, bsd3, cc0 or polar.
while IFS=: read -r group artifact version; do
    pom=$(pom_of "$group" "$artifact" "$version")
    # A BOM only constrains versions; it puts no code in the app.
    if [ -n "$pom" ] && tr -d '\r\n' < "$pom" | grep -q '<packaging>pom</packaging>'; then
        continue
    fi
    coordinate="$group:$artifact:$version"
    case "$group:$artifact" in
        # Its POM declares no licence; Polar's own licence covers it.
        com.github.polarofficial:polar-ble-sdk) echo "$coordinate" >> "$work/polar"; continue ;;
    esac
    names=$(licence_names "$group" "$artifact" "$version" | tr 'A-Z' 'a-z')
    if printf '%s' "$names" | grep -q 'apache' && printf '%s' "$names" | grep -q '2'; then
        echo "$coordinate" >> "$work/apache"
    elif printf '%s' "$names" | grep -q 'cc0'; then
        echo "$coordinate" >> "$work/cc0"
    elif [ "$group:$artifact" = com.google.protobuf:protobuf-javalite ] &&
        printf '%s' "$names" | grep -q 'bsd-3-clause'; then
        # docs/licenses/BSD-3-Clause-protobuf.txt carries protobuf's own copyright line.
        echo "$coordinate" >> "$work/bsd3"
    else
        echo "$coordinate has a licence this script does not know: '$names'." >&2
        echo "Add its text to $licences/ and a case to scripts/third-party-notices.sh." >&2
        exit 1
    fi

    # Apache 2.0 section 4(d): a NOTICE file's attribution notices go with the binaries.
    for file in "$cache/$group/$artifact/$version"/*/*.jar "$cache/$group/$artifact/$version"/*/*.aar; do
        [ -f "$file" ] || continue
        case "$file" in *-sources.jar | *-javadoc.jar | *-samples*) continue ;; esac
        archive=$file
        case "$file" in
            *.aar) unzip -p "$file" classes.jar > "$work/classes.jar" 2>/dev/null || continue
                   archive="$work/classes.jar" ;;
        esac
        for entry in $(unzip -Z1 "$archive" 2>/dev/null | grep -i '^META-INF/NOTICE' || true); do
            {
                printf '### `%s`\n\n```\n' "$coordinate"
                unzip -p "$archive" "$entry" | tr -d '\r'
                printf '```\n\n'
            } >> "$work/notices"
        done
    done
done < "$work/coordinates"

list() { # list file: each coordinate as a bullet, or nothing if there are none
    if [ -f "$1" ]; then sed 's/^\(.*\)$/- `\1`/' "$1"; fi
}

text() { # text file: a licence text as a fenced block
    printf '```\n'
    tr -d '\r' < "$1"
    printf '```\n'
}

cat <<'EOF'
# Third-party notices

The release build of Jabjotjai includes the third-party software listed here, each used under the
licence named. This file is generated by `scripts/third-party-notices.sh` from the build's own
dependencies; edit that script, not this file.

## Polar BLE SDK

Used under Polar's software development kit licence, reproduced in full under "Licence texts".
Polar and Polar product names are trademarks of Polar Electro Oy. Jabjotjai is not affiliated with,
endorsed by or sponsored by Polar Electro Oy.

EOF
list "$work/polar"
cat <<'EOF'

## Apache License 2.0

The text is under "Licence texts".

EOF
list "$work/apache"
if [ -f "$work/notices" ]; then
    printf '\n### Notices\n\nThe NOTICE files these libraries carry, as section 4(d) of the licence asks.\n\n'
    cat "$work/notices"
fi
if [ -f "$work/bsd3" ]; then
    printf '\n## BSD 3-Clause License\n\n'
    list "$work/bsd3"
    printf '\n'
    text "$licences/BSD-3-Clause-protobuf.txt"
fi
if [ -f "$work/cc0" ]; then
    printf '\n## CC0 1.0\n\nDedicated to the public domain under CC0 1.0,\nhttps://creativecommons.org/publicdomain/zero/1.0/.\n\n'
    list "$work/cc0"
fi
cat <<'EOF'

## SIL Open Font License 1.1

- JetBrains Mono, version 2.211, the monitor's font (`app/src/main/res/font/jetbrains_mono.ttf`).
  The app also carries this licence text itself, in `assets/licenses/jetbrains-mono-OFL.txt`.

EOF
# The app's own copy, so the notices quote exactly the text the app ships.
text app/src/main/assets/licenses/jetbrains-mono-OFL.txt
printf '\n## Licence texts\n\n### Apache License 2.0\n\n'
text "$licences/Apache-2.0.txt"
printf '\n### Polar software development kit licence\n\n'
text "$licences/Polar-SDK-License.txt"
