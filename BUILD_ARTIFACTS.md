# Build artifacts

The `Build Slope Connector` GitHub Actions workflow publishes two artifacts after every successful pull-request or main-branch build:

- `slope-connector-build`: compiled mod jar and generated sources jar.
- `slope-connector-0.9.18-complete-source`: complete, directly buildable source tree including Gradle files, workflow, resources, and the embedded 0.9.10 base jar.
