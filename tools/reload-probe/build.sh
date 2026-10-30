#!/bin/bash
# Baut ReloadProbe-Bungee.jar und ReloadProbe-Velocity.jar mit javac, ohne Gradle.
set -euo pipefail
cd "$(dirname "$0")"
JAVA=${JAVA_HOME:-/media/lucasthcr/Daten/java/current}/bin
M=/media/lucasthcr/Daten/gradle-home/caches/modules-2/files-2.1/net.md-5
BCP=$(find $M -name 'bungeecord-*-1.21-R0.4.jar' ! -name '*sources*' | tr '\n' ':')
VCP=${VELOCITY_JAR:-../../../PaperProxy-test/pp.jar}
rm -rf out && mkdir -p out/b out/v
$JAVA/javac --release 17 -nowarn -cp "$BCP" -d out/b bungee/net/paperstream/reloadprobe/*.java 2>&1 | grep -v '^Note' || true
cp bungee/bungee.yml out/b/
$JAVA/javac --release 17 -nowarn -proc:none -cp "$VCP" -d out/v velocity/net/paperstream/reloadprobe/*.java 2>&1 | grep -v '^Note' || true
cp velocity/velocity-plugin.json out/v/
$JAVA/jar cf ReloadProbe-Bungee.jar -C out/b .
$JAVA/jar cf ReloadProbe-Velocity.jar -C out/v .
ls -la ReloadProbe-*.jar
