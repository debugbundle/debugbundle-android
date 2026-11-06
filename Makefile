SHELL := /bin/sh

GRADLE_RUNNER ?= docker
GRADLE_IMAGE ?= gradle:9.4.1-jdk21
DOCKER_PLATFORM ?= linux/amd64
WORKDIR := /workspace
VERSION ?=
HOST_ANDROID_SDK_ROOT := $(CURDIR)/.android-sdk
HOST_ANDROID_USER_HOME := $(CURDIR)/.android-home
DOCKER_ANDROID_SDK_ROOT := $(WORKDIR)/.android-sdk
DOCKER_ANDROID_USER_HOME := $(WORKDIR)/.android-home
GRADLE_CACHE_DIR ?= $(WORKDIR)/.gradle
DOCKER_RUN = docker run --rm -t --platform "$(DOCKER_PLATFORM)" \
	-v "$(CURDIR):$(WORKDIR)" \
	-w "$(WORKDIR)" \
	-e ANDROID_SDK_ROOT="$(DOCKER_ANDROID_SDK_ROOT)" \
	-e ANDROID_HOME="$(DOCKER_ANDROID_SDK_ROOT)" \
	-e ANDROID_USER_HOME="$(DOCKER_ANDROID_USER_HOME)" \
	-e GRADLE_USER_HOME="$(GRADLE_CACHE_DIR)" \
	$(GRADLE_IMAGE)

ifeq ($(GRADLE_RUNNER),wrapper)
GRADLE_CMD = ANDROID_SDK_ROOT="$(HOST_ANDROID_SDK_ROOT)" ANDROID_HOME="$(HOST_ANDROID_SDK_ROOT)" ANDROID_USER_HOME="$(HOST_ANDROID_USER_HOME)" sh scripts/with-android-sdk.sh ./gradlew --no-daemon --console=plain --no-watch-fs
SMOKE_CMD = ANDROID_SDK_ROOT="$(HOST_ANDROID_SDK_ROOT)" ANDROID_HOME="$(HOST_ANDROID_SDK_ROOT)" ANDROID_USER_HOME="$(HOST_ANDROID_USER_HOME)" ./smoke/run-app-driven-smoke.sh
SHELL_CMD = ANDROID_SDK_ROOT="$(HOST_ANDROID_SDK_ROOT)" ANDROID_HOME="$(HOST_ANDROID_SDK_ROOT)" ANDROID_USER_HOME="$(HOST_ANDROID_USER_HOME)" sh scripts/with-android-sdk.sh sh
else
GRADLE_CMD = $(DOCKER_RUN) sh scripts/with-android-sdk.sh ./gradlew --no-daemon --console=plain --no-watch-fs
SMOKE_CMD = $(DOCKER_RUN) ./smoke/run-app-driven-smoke.sh
SHELL_CMD = $(DOCKER_RUN) sh
endif

.PHONY: verify
verify:
	$(GRADLE_CMD) test coverageReport verifyKotlinMetadataCompatibility
	python3 ./scripts/check-coverage.py ./build/reports/jacoco/coverageReport/coverageReport.xml

.PHONY: test
test: verify

.PHONY: test-focused
test-focused:
	$(GRADLE_CMD) :debugbundle-android-core:test --tests "$(TEST_CLASS)"

.PHONY: build
build:
	$(GRADLE_CMD) build

.PHONY: publish-local
publish-local:
	$(GRADLE_CMD) publishToMavenLocal

.PHONY: publish-central
publish-central:
	test -n "$(VERSION)"
	$(GRADLE_CMD) -PVERSION_NAME="$(VERSION)" publishToMavenCentral

.PHONY: smoke
smoke:
	$(SMOKE_CMD)

.PHONY: smoke-connected
smoke-connected:
	$(SMOKE_CMD) --connected

.PHONY: evidence-device-apks
evidence-device-apks:
	$(GRADLE_CMD) -p smoke/android-consumer -PdebugbundleVersion=3.1.1 -PdebugbundleRepoUrl=/workspace/.tmp/evidence-maven test assembleDebug assembleDebugAndroidTest

.PHONY: smoke-published
smoke-published:
	test -n "$(VERSION)"
	$(SMOKE_CMD) --published "$(VERSION)"

.PHONY: shell
shell:
	$(SHELL_CMD)

.PHONY: test-runtime-focused
test-runtime-focused:
	$(GRADLE_CMD) :debugbundle-android:testDebugUnitTest --tests "$(TEST_CLASS)"

.PHONY: evidence-regression
evidence-regression:
	$(GRADLE_CMD) :debugbundle-android-core:test --tests "com.debugbundle.android.*Evidence*Test" :debugbundle-android:testDebugUnitTest --tests "com.debugbundle.android.runtime.*Evidence*Test" --tests "com.debugbundle.android.runtime.DebugBundleFlushWorkerTest"

.PHONY: test-timber-focused
test-timber-focused:
	$(GRADLE_CMD) :debugbundle-android-timber:testDebugUnitTest --tests "$(TEST_CLASS)"

.PHONY: test-network-focused
test-network-focused:
	$(GRADLE_CMD) --continue :debugbundle-android-okhttp:test :debugbundle-android-ktor-client:test

.PHONY: evidence-smoke
evidence-smoke:
	$(DOCKER_RUN) env DEBUGBUNDLE_SMOKE_ARTIFACTS=/workspace/.tmp/evidence-maven ./smoke/run-app-driven-smoke.sh

.PHONY: evidence-manifest
evidence-manifest:
	python3 ./scripts/evidence-artifact-manifest.py

.PHONY: evidence-performance
evidence-performance:
	$(GRADLE_CMD) :debugbundle-android-core:evidencePerformance
