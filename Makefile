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
DOCKER_RUN = docker run --rm -t --platform "$(DOCKER_PLATFORM)" \
	-v "$(PWD):$(WORKDIR)" \
	-w "$(WORKDIR)" \
	-e ANDROID_SDK_ROOT="$(DOCKER_ANDROID_SDK_ROOT)" \
	-e ANDROID_HOME="$(DOCKER_ANDROID_SDK_ROOT)" \
	-e ANDROID_USER_HOME="$(DOCKER_ANDROID_USER_HOME)" \
	$(GRADLE_IMAGE)

ifeq ($(GRADLE_RUNNER),wrapper)
GRADLE_CMD = ANDROID_SDK_ROOT="$(HOST_ANDROID_SDK_ROOT)" ANDROID_HOME="$(HOST_ANDROID_SDK_ROOT)" ANDROID_USER_HOME="$(HOST_ANDROID_USER_HOME)" sh scripts/with-android-sdk.sh ./gradlew --no-daemon
SMOKE_CMD = ANDROID_SDK_ROOT="$(HOST_ANDROID_SDK_ROOT)" ANDROID_HOME="$(HOST_ANDROID_SDK_ROOT)" ANDROID_USER_HOME="$(HOST_ANDROID_USER_HOME)" ./smoke/run-app-driven-smoke.sh
SHELL_CMD = ANDROID_SDK_ROOT="$(HOST_ANDROID_SDK_ROOT)" ANDROID_HOME="$(HOST_ANDROID_SDK_ROOT)" ANDROID_USER_HOME="$(HOST_ANDROID_USER_HOME)" sh scripts/with-android-sdk.sh sh
else
GRADLE_CMD = $(DOCKER_RUN) sh scripts/with-android-sdk.sh ./gradlew --no-daemon
SMOKE_CMD = $(DOCKER_RUN) ./smoke/run-app-driven-smoke.sh
SHELL_CMD = $(DOCKER_RUN) sh
endif

.PHONY: verify
verify:
	$(GRADLE_CMD) test

.PHONY: test
test: verify

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

.PHONY: smoke-published
smoke-published:
	test -n "$(VERSION)"
	$(SMOKE_CMD) --published "$(VERSION)"

.PHONY: shell
shell:
	$(SHELL_CMD)
