# Third Party Lock

`reaktor-security` treats Cisco MLS++ as a vendored pinned dependency. It is the only E2EE protocol engine allowed in this module.

| Dependency | Path | Commit | License | Role |
| --- | --- | --- | --- | --- |
| Cisco MLS++ | `cpp/external/mlspp` | `fc724c3100ce3b5d8565dbd6d93648a440991a8c` | BSD-2-Clause | MLS protocol implementation |
| BoringSSL | `cpp/external/boringssl` | `3769c110eab4ddbc0c159fadb4f1d0d98b2cf0cc` | Apache-2.0 | Crypto backend on Android, Apple and the host |
| nlohmann/json | `cpp/external/json` | `cba5dc0ed807e36446d83afbcbc936128a8a54b7` | MIT | Header-only JSON dependency required by MLS++ |
| GoogleTest | `cpp/external/googletest` | `dc3c9eda2f02ba32de9329dd27ace7e527f492dc` | BSD-3-Clause | Native test framework |

License allowlist for this module:

- BSD-2-Clause
- BSD-3-Clause
- MIT
- Apache-2.0 for the pinned BoringSSL crypto backend and build/test tooling

Not allowed:

- unpinned MLS++ checkouts
- Signal protocol provider dependencies
- GPL or AGPL dependencies in shipped artifacts
