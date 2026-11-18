# Everything runs in pinned containers: nothing to install on the host but docker.
#   ANDROID_IMAGE: JDK 21 + Android SDK (platform 36, build-tools 36.0.0), Cirrus Labs' maintained image
#   NODE_IMAGE:    Node 22, for bundling and testing the extension's extractor
# The same images run in CI (.forgejo/workflows/ci.yaml); keep the digests in step.
ANDROID_IMAGE := ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31
NODE_IMAGE := node:22-bookworm@sha256:e13726ca604184d7a91fcacab1fc09eefd642dc28e79ac34e85540dd93b78cfb

# As the calling user (no root-owned files in the checkout); Gradle's and Android's caches live in the checkout
# (git-ignored) so builds after the first are quick.
# ANDROID_USER_HOME keeps the debug signing key between runs (the container's user has no home of its own), so a
# new debug build installs over the previous one.
DOCKER_RUN := docker run --rm -u $(shell id -u):$(shell id -g) -e HOME=/work/.home \
	-e GRADLE_USER_HOME=/work/.gradle-home -e ANDROID_USER_HOME=/work/.home/.android -v $(CURDIR):/work -w /work
GRADLE := $(DOCKER_RUN) $(ANDROID_IMAGE) ./gradlew --console=plain
NODE := $(DOCKER_RUN) -w /work/extractor $(NODE_IMAGE)

APK := app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK := app/build/outputs/apk/release/app-release-unsigned.apk

.PHONY: build apk release-apk test lint check extractor extractor-check extractor-test extractor-deps clean

## build: compile the debug app
build:
	$(GRADLE) assembleDebug

## apk: the debug APK, copied to dist/unfret-<version>-debug.apk
apk: build
	@mkdir -p dist
	@version="$$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"; \
	  cp $(APK) "dist/unfret-$$version-debug.apk" && echo "dist/unfret-$$version-debug.apk"

## release-apk: the release APK, unsigned, at dist/unfret-<version>-unsigned.apk: the same bytes the release workflow
## signs (its release.json names their SHA-256, unsigned_sha256)
release-apk:
	$(GRADLE) assembleRelease
	@mkdir -p dist
	@version="$$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"; \
	  cp $(RELEASE_APK) "dist/unfret-$$version-unsigned.apk" && sha256sum "dist/unfret-$$version-unsigned.apk"

## test: unit tests (JVM) and the bundled extractor over the extension's fixtures (node + jsdom)
test: extractor-test
	$(GRADLE) testDebugUnitTest

## lint: Android lint (warnings are errors) and the check that the bundle is the pinned extension commit's
lint: extractor-check
	$(GRADLE) lintDebug

## check: everything CI runs
check: lint test build

extractor-deps:
	./extractor/fetch-extension.sh
	$(NODE) npm ci --no-audit --no-fund

## extractor: rebuild app/src/main/assets/unfret-extract.js from the extension commit in extractor/EXTENSION_COMMIT
extractor: extractor-deps
	$(NODE) npm run build

extractor-check: extractor-deps
	$(NODE) npm run check

extractor-test: extractor-deps
	$(NODE) npm test

clean:
	$(GRADLE) clean
	rm -rf dist extractor/.extension extractor/node_modules
