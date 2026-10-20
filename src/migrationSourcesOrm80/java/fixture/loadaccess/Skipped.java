package fixture.loadaccess;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.metamodel.SingularAttribute;
import org.hibernate.*;

/// Inputs that must retain the complete original candidate and receive diagnostics.
/// @author Steve Ebersole
class Skipped {
    Object idReference(Session s) { return s.byId(Skipped.class).getReference(1L); }
    Object naturalReference(Session s) { return s.bySimpleNaturalId(Skipped.class).getReference("x"); }
    Object namedReference(Session s) { return s.byNaturalId(Skipped.class).using("code", "x").getReference(); }
    Object entityName(Session s) { return s.byId("example").load(1L); }
    Object namedEntityName(Session s) { return s.byNaturalId("example").using("code", "x").load(); }
    IdentifierLoadAccess<Skipped> escaped(Session s) { return s.byId(Skipped.class); }
    Function<Object, Skipped> reference(Session s) { return s.byId(Skipped.class)::load; }
    Function<Class<Skipped>, IdentifierLoadAccess<Skipped>> factoryReference(Session s) { return s::byId; }
    Object graph(Session s, EntityGraph<Skipped> graph) { return s.byId(Skipped.class).withFetchGraph(graph).load(1L); }
    Object loadGraph(Session s, EntityGraph<Skipped> graph) { return s.byId(Skipped.class).withLoadGraph(graph).load(1L); }
    Object implicitGraph(Session s, EntityGraph<Skipped> graph) { return s.byId(Skipped.class).with(graph).load(1L); }
    Object semanticGraph(Session s, EntityGraph<Skipped> graph) { return s.byId(Skipped.class).with(graph, org.hibernate.graph.GraphSemantic.FETCH).load(1L); }
    Object locks(Session s, LockOptions options) { return s.byId(Skipped.class).with(options).load(1L); }
    Object profile(Session s) { return s.byId(Skipped.class).enableFetchProfile("x").load(1L); }
    Object disabledProfile(Session s) { return s.byId(Skipped.class).disableFetchProfile("x").load(1L); }
    Object repeated(Session s) { return s.byId(Skipped.class).withReadOnly(true).withReadOnly(false).load(1L); }
    Object batchZero(Session s) { return s.byMultipleIds(Skipped.class).withBatchSize(0).multiLoad(1L); }
    Object batchDynamic(Session s, int n) { return s.byMultipleNaturalId(Skipped.class).withBatchSize(n).multiLoad("x"); }
    Object severalKeys(Session s) { return s.byNaturalId(Skipped.class).using("a", "x").using("b", "y").load(); }
    Object metamodel(Session s, SingularAttribute<Skipped, String> attribute) { return s.byNaturalId(Skipped.class).using(attribute, "x").load(); }
    Object noKey(Session s) { return s.byNaturalId(Skipped.class).load(); }
    Object nullableOption(Session s, CacheMode mode) { return s.byId(Skipped.class).with(mode).load(1L); }
    Object order(Session s) { return s.byId(Skipped.class).withReadOnly(flag()).load(key()); }
    Object mapMutation(Session s, Map<String, Object> map) { return s.byNaturalId(Skipped.class).using(map).setSynchronizationEnabled(flag()).load(); }
    Object comment(Session s) { return s.byId(Skipped.class)/*retain*/.load(1L); }
    private boolean flag() { return true; }
    private Long key() { return 1L; }
}
