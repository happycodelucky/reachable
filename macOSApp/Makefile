# Reachable — macOSApp Makefile.
#
# Mirrors iOSApp/Makefile — same targets, same workflow:
#
#   make            — generate macOSApp.xcodeproj from project.yml.
#   make spm        — rebuild the local SPM Package.swift via Gradle.
#   make all        — both, plus open the project in Xcode.

.PHONY: default spm xcodeproj all clean

default: xcodeproj

xcodeproj: macOSApp.xcodeproj/project.pbxproj

macOSApp.xcodeproj/project.pbxproj: project.yml
	xcodegen generate --spec project.yml

spm:
	cd .. && ./gradlew :reachable:spmDevBuild --no-daemon

all: spm xcodeproj
	open macOSApp.xcodeproj

clean:
	rm -rf macOSApp.xcodeproj
