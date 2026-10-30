#!/bin/bash
f="./app/build/outputs/apk/prod/release/app-prod-release-unsigned.apk"
if [ ! -s "$f" ]
then
echo "APK doesn't exist"
exit 5
fi
s=`du -B 1048576 "$f" | cut -f1 | tr -dc '0-9'`
# The Garmin Connect IQ bridge and opt-in PumpX2 prototype add phone-side
# runtime dependencies. The current release APK is 23 MiB, so allow 24 MiB
# while retaining a hard ceiling against unexpected APK growth.
if [ $s -lt 15 ] || [ $s -gt 24 ]
then
echo "APK out of size range @ ${s}M"
exit 5
fi
f="./wear/build/outputs/apk/prod/release/wear-prod-release-unsigned.apk"
if [ ! -s "$f" ]
then
echo "Wear APK doesn't exist"
exit 5
fi
s=`du -B 1048576 "$f" | cut -f1 | tr -dc '0-9'`
if [ $s -lt 2 ] || [ $s -gt 4 ]
then
echo "Wear APK out of size range @ ${s}M"
exit 5
fi
