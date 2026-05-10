# Reachable — iOSApp Makefile.
#
# Convenience entrypoint for the iOS sample app build cycle. Three targets:
#
#   make            — generate iOSApp.xcodeproj from project.yml (xcodegen).
#   make spm        — rebuild the local SPM Package.swift via the Gradle task.
#   make all        — both, plus open the project in Xcode.
#
# Iteration loop after editing Kotlin in /reachable/src/...:
#   make spm        # rebuilds debug XCFramework + updates root Package.swift
#   then re-build the app target inside Xcode (no need to regen the project).

.PHONY: default spm xcodeproj all clean

default: xcodeproj

xcodeproj: iOSApp.xcodeproj/project.pbxproj

iOSApp.xcodeproj/project.pbxproj: project.yml
	xcodegen generate --spec project.yml

spm:
	cd .. && ./gradlew :reachable:spmDevBuild --no-daemon

all: spm xcodeproj
	open iOSApp.xcodeproj

clean:
	rm -rf iOSApp.xcodeproj
