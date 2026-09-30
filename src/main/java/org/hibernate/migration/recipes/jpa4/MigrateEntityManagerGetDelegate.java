package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.support.MigrationSupport;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;

/// Replaces Hibernate-backed EntityManager delegate calls with unwrap(java.lang.Object.class).
///
/// @author Jennifer Joby
/// @author Steve Ebersole
public class MigrateEntityManagerGetDelegate extends ScanningRecipe<Set<String>> {
	private final transient SkippedMigrations skipped = new SkippedMigrations( this );

	@Override
	public @NonNull String getDisplayName() {
		return "Replace EntityManager.getDelegate() with unwrap(java.lang.Object.class)";
	}

	@Override
	public @NonNull String getDescription() {
		return "Preserves Hibernate delegate semantics while migrating deprecated EntityManager calls.";
	}

	@Override
	public Set<String> getInitialValue(ExecutionContext ctx) {
        return new HashSet<>();
    }

    /// Collects owners of member types named java, including declarations in other source files.
    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<String> owners) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext ctx) {
                if ("java".equals(cd.getSimpleName()) && cd.getType() != null
                        && cd.getType().getOwningClass() != null) {
                    owners.add(cd.getType().getOwningClass().getFullyQualifiedName());
                }
                return super.visitClassDeclaration(cd, ctx);
            }
        };
    }

    @Override
    public @NonNull TreeVisitor<?, ExecutionContext> getVisitor(Set<String> owners) {
		return new MigrationSupport.JavaVisitor( this, skipped ) {
			private final JavaTemplate argument = JavaTemplate.builder( "java.lang.Object.class" ).build();

			private boolean isDelegate(JavaType.Method method) {
				return method != null && "getDelegate".equals( method.getName() ) && method.getParameterTypes()
						.isEmpty()
					   && TypeUtils.isAssignableTo( "jakarta.persistence.EntityManager", method.getDeclaringType() );
			}

			@Override
			public J.@NonNull MemberReference visitMemberReference(
					J.@NonNull MemberReference ref,
					@NonNull ExecutionContext ctx) {
				J.MemberReference r = super.visitMemberReference( ref, ctx );
				if ( r.getMethodType() == null
                        && "getDelegate".equals( r.getReference().getSimpleName() )
                        && knownEntityManager( r.getContaining() ) ) {
					skip( r, ctx, "getDelegate", "MISSING_TYPE_ATTRIBUTION",
							"Resolve the source EntityManager method before reviewing this reference." );
				}
                if ( isDelegate( r.getMethodType() ) ) {
                    skip( r, ctx, "getDelegate", "UNSUPPORTED_METHOD_REFERENCE",
                            "Migrate this method reference manually." );
                }
				return r;
			}

			@Override
			public J.@NonNull MethodInvocation visitMethodInvocation(
					J.@NonNull MethodInvocation method,
					@NonNull ExecutionContext ctx) {
				J.MethodInvocation m = super.visitMethodInvocation( method, ctx );
				if ( m.getMethodType() == null && "getDelegate".equals( m.getSimpleName() )
                        && (m.getArguments().isEmpty() || m.getArguments().size() == 1 && m.getArguments().get( 0 ) instanceof J.Empty)
                        && knownEntityManager( m.getSelect() ) ) {
					skip( m, ctx, "getDelegate", "MISSING_TYPE_ATTRIBUTION",
							"Resolve the source EntityManager method before migrating this call." );
					return m;
				}
                if ( !isDelegate( m.getMethodType() ) ) {
                    return m;
                }
				if ( !TypeUtils.isOfClassType( m.getMethodType().getDeclaringType(),
						"jakarta.persistence.EntityManager" ) ) {
					skip( m, ctx, "getDelegate", "UNSUPPORTED_METHOD_OVERRIDE",
							"The application override may have different delegate semantics." );
					return m;
				}
				if ( shadowsJava() ) {
					skip( m, ctx, "getDelegate", "NAME_RESOLUTION_CONFLICT",
							"A visible declaration shadows java in java.lang.Object.class." );
					return m;
				}
				J.MethodInvocation changed = argument.apply( updateCursor( m ), m.getCoordinates().replaceArguments() );
				// Replacing only the arguments retains implicit, this, super, and side-effecting receivers.
				JavaType.Method unwrap = m.getMethodType().withName( "unwrap" )
						.withParameterNames( Collections.singletonList( "type" ) )
						.withParameterTypes( Collections.singletonList( changed.getArguments().get( 0 ).getType() ) );
				changed = changed.withName( m.getName().withSimpleName( "unwrap" ).withType( unwrap ) )
						.withMethodType( unwrap );
				if ( m.getArguments().size() == 1 && m.getArguments().get( 0 ) instanceof J.Empty ) {
					changed = changed.withArguments( Collections.singletonList( changed.getArguments().get( 0 )
							.withPrefix( m.getArguments().get( 0 ).getPrefix() ) ) );
				}
				return changed;
			}

			private boolean knownEntityManager(Expression receiver) {
                if ( receiver != null ) {
                    return TypeUtils.isAssignableTo( "jakarta.persistence.EntityManager", receiver.getType() );
                }
				J.ClassDeclaration owner = getCursor().firstEnclosing( J.ClassDeclaration.class );
				return owner != null && TypeUtils.isAssignableTo( "jakarta.persistence.EntityManager",
						owner.getType() );
			}

			private boolean shadowsJava() {
				for ( J.Import imp : input.getImports() ) {
                    if ( imp.getTypeName().endsWith( ".java" ) ) {
                        return true;
                    }
				}
				for ( J.ClassDeclaration type : input.getClasses() ) {
                    if ( "java".equals( type.getSimpleName() ) ) {
                        return true;
                    }
				}
				for ( Cursor c = getCursor().getParent(); c != null; c = c.getParent() ) {
					Object value = c.getValue();
					if ( value instanceof J.MethodDeclaration ) {
                        if (hasJavaTypeParameter(((J.MethodDeclaration) value).getTypeParameters())) {
                            return true;
                        }
                        for ( Statement p : ((J.MethodDeclaration) value).getParameters() ) {
                            if ( namedJava( p ) ) {
                                return true;
                            }
                        }
					}
					if ( value instanceof J.Block ) {
                        for ( Statement statement : ((J.Block) value).getStatements() ) {
                            if ( namedJava( statement ) ) {
                                return true;
                            }
                        }
					}
                    if (value instanceof J.ClassDeclaration) {
                        J.ClassDeclaration cd = (J.ClassDeclaration) value;
                        if ("java".equals(cd.getSimpleName()) || hasJavaTypeParameter(cd.getTypeParameters())) {
                            return true;
                        }
                        for (String owner : owners) {
                            if (TypeUtils.isAssignableTo(owner, cd.getType())) {
                                return true;
                            }
                        }
                    }
				}
				return false;
			}

            private boolean hasJavaTypeParameter(List<J.TypeParameter> parameters) {
                if (parameters != null) {
                    for (J.TypeParameter parameter : parameters) {
                        if (parameter.getName() instanceof J.Identifier
                                && "java".equals(((J.Identifier) parameter.getName()).getSimpleName())) {
                            return true;
                        }
                    }
                }
                return false;
            }

			private boolean namedJava(Statement statement) {
                if ( statement instanceof J.ClassDeclaration ) {
                    return "java".equals( ((J.ClassDeclaration) statement).getSimpleName() );
                }
				if ( statement instanceof J.VariableDeclarations ) {
					for ( J.VariableDeclarations.NamedVariable v : ((J.VariableDeclarations) statement).getVariables() ) {
						if ( "java".equals( v.getSimpleName() ) ) return true;
					}
				}
				return false;
			}
		};
	}
}
