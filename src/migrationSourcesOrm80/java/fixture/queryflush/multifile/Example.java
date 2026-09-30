package fixture.queryflush.multifile;

/// Input for the QueryFlushMode multifile scenario.
/// @author Steve Ebersole
class Example {
    org.hibernate.query.QueryFlushMode mode() { return Modes.mode; }
}
