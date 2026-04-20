SHELL := /bin/bash

GRADLEW := ./gradlew
GRADLE := $(GRADLEW) --no-daemon
APK_DEBUG := app/build/outputs/apk/debug/app-debug.apk

LOCAL_PROPERTIES := local.properties
SDK_DIR_FROM_LOCAL := $(shell [ -f $(LOCAL_PROPERTIES) ] && sed -n 's/^sdk\.dir=//p' $(LOCAL_PROPERTIES) | tail -n 1)

ifneq ($(strip $(SDK_DIR_FROM_LOCAL)),)
export ANDROID_HOME ?= $(SDK_DIR_FROM_LOCAL)
endif

.PHONY: help doctor build clean rebuild install test lint signing-report

help:
	@echo "Available targets:"
	@echo "  make build           - Build debug APK"
	@echo "  make install         - Build and install debug APK (adb required)"
	@echo "  make test            - Run unit tests"
	@echo "  make lint            - Run Android lint"
	@echo "  make signing-report  - Show SHA-1/SHA-256 signing report"
	@echo "  make clean           - Clean build outputs"
	@echo "  make rebuild         - Clean and build debug APK"
	@echo "  make doctor          - Show key environment values"

doctor:
	@echo "ANDROID_HOME=$(ANDROID_HOME)"
	@echo "JAVA_HOME=$(JAVA_HOME)"
	@echo "sdk.dir(from local.properties)=$(SDK_DIR_FROM_LOCAL)"
	@echo "gradle wrapper=$(GRADLEW)"

build:
	$(GRADLE) assembleDebug
	@echo ""
	@echo "Built APK: $(APK_DEBUG)"

clean:
	$(GRADLE) clean

rebuild: clean build

install: build
	@command -v adb >/dev/null || (echo "adb not found. Install Android platform-tools."; exit 1)
	adb install -r $(APK_DEBUG)

test:
	$(GRADLE) test

lint:
	$(GRADLE) lint

signing-report:
	$(GRADLE) signingReport
