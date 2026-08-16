@echo off
./gradlew :TMessagesProj_AppStandalone:assembleAfatRelease -x test -x :TMessagesProj_AppTests:generateScheme
explorer .\TMessagesProj_AppStandalone\build\outputs\apk\afat\release