mapping-8.0.xsd is copied without modification from hibernate/hibernate-orm:
  revision: 15fa212df2cbde5891b636cefd9699ac6ec985de
  path: hibernate-core/src/main/resources/org/hibernate/xsd/mapping/mapping-8.0.xsd
  change: HHH-20959 / https://github.com/hibernate/hibernate-orm/pull/13590

The pinned 8.0.0.Beta2 runtime predates the HTTPS namespace change. XML tests
use this fixture only when that runtime's mapping-8.0.xsd still declares HTTP.
The mapping bootstrap integration test explicitly skips such runtimes; it
must pass against Beta4 once released. Remove this fallback and fixture when
the pinned target runtime ships the HTTPS schema.
