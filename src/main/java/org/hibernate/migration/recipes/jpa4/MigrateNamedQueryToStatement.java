package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.support.MigrationSupport;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;

/// Converts bounded literal DML annotations while retaining unsupported queries for review.
///
/// @author Jennifer Joby
/// @author Steve Ebersole
public class MigrateNamedQueryToStatement extends Recipe {
	private static final String JPA = "jakarta.persistence.";

	private final transient SkippedMigrations skipped = new SkippedMigrations( this );

	@Override
	public @NonNull String getDisplayName() {
		return "Migrate DML named queries to JPA 4 statements";
	}

	@Override
	public @NonNull String getDescription() {
		return "Converts supported Hibernate DML named queries and reports unsupported forms without altering them.";
	}

	@Override
	public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
		return new MigrationSupport.JavaVisitor( this, skipped ) {
			private final Set<UUID> handled = new HashSet<>();

			private String kind(J.Annotation ann, ExecutionContext ctx) {
				for ( String name : Arrays.asList( "NamedQuery", "NamedNativeQuery", "NamedQueries",
						"NamedNativeQueries" ) ) {
					if ( matches( ann, JPA + name, ctx ) ) {
						return name;
					}
				}
				return null;
			}

			@Override
			public J.@NonNull Annotation visitAnnotation(
					J.@NonNull Annotation ann,
					@NonNull ExecutionContext ctx) {
				J.Annotation a = super.visitAnnotation( ann, ctx );
				String kind = kind( a, ctx );
				if ( kind != null && !handled.contains( a.getId() ) ) {
					skip( a, ctx, kind, "UNSUPPORTED_ANNOTATION_LOCATION",
							"Only class and interface declarations are supported." );
				}
				return a;
			}

			@Override
			public J.@NonNull ClassDeclaration visitClassDeclaration(
					J.@NonNull ClassDeclaration cd,
					@NonNull ExecutionContext ctx) {
				List<J.Annotation> result = new ArrayList<>();
				boolean changed = false;
				for ( J.Annotation ann : cd.getLeadingAnnotations() ) {
					String kind = kind( ann, ctx );
					if ( kind == null ) {
						result.add( ann );
						continue;
					}
					handled.add( ann.getId() );
					boolean nativeQuery = kind.contains( "Native" );
					String source = nativeQuery ? "NamedNativeQuery" : "NamedQuery";
					String target = nativeQuery ? "NamedNativeStatement" : "NamedStatement";
					boolean targetContainer = cd.getLeadingAnnotations().stream()
							.anyMatch( a -> TypeUtils.isOfClassType( a.getType(), JPA + target + "s" ) );
					if ( kind.equals( source ) ) {
						if ( eligible( ann, nativeQuery, targetContainer, ctx ) ) {
							result.add( convert( ann, source, target ) );
							changed = true;
						}
						else {
							result.add( ann );
						}
						continue;
					}
					List<J.Annotation> entries = entries( ann, source );
					if ( entries == null ) {
						markEntries( ann );
						skip( ann, ctx, kind, "UNSUPPORTED_ANNOTATION_SHAPE",
								"Cannot safely decompose this named-query container." );
						result.add( ann );
						continue;
					}
					List<J.Annotation> keep = new ArrayList<>(), extracted = new ArrayList<>();
					for ( J.Annotation entry : entries ) {
						handled.add( entry.getId() );
						if ( eligible( entry, nativeQuery, targetContainer, ctx ) ) {
							extracted.add( convert( entry, source, target ) );
						}
						else {
							keep.add( entry );
						}
					}
					if ( extracted.isEmpty() ) {
						result.add( ann );
						continue;
					}
					changed = true;
					List<J.Annotation> replacements = new ArrayList<>();
					if ( !keep.isEmpty() ) {
						replacements.add( rebuild( ann, keep ) );
					}
					for ( int i = 0; i < extracted.size(); i++ ) {
						J.Annotation entry = extracted.get( i );
						Space prefix = Space.format( lineBreak() + indentation( cd.getPrefix() ) );
						if ( keep.isEmpty() && i == 0 ) {
							prefix = ann.getPrefix();
						}
						entry = entry.withPrefix( MigrationSupport.comments( prefix, entry.getPrefix() ) );
						replacements.add( entry );
					}
					// Punctuation disappears during extraction. Rescue its comments without duplicating entry comments.
					Set<Comment> retained = Collections.newSetFromMap( new IdentityHashMap<Comment, Boolean>() );
					for ( J.Annotation replacement : replacements ) {
						retained.addAll( commentsOf( replacement ) );
					}
					List<Comment> missing = new ArrayList<>();
					for ( Comment comment : commentsOf( ann ) ) {
						if ( retained.add( comment ) ) {
							missing.add( comment );
						}
					}
					if ( !missing.isEmpty() ) {
						J.Annotation first = replacements.get( 0 );
						List<Comment> comments = new ArrayList<>( first.getPrefix().getComments() );
						comments.addAll( missing );
						replacements.set( 0, first.withPrefix( first.getPrefix().withComments( comments ) ) );
					}
					result.addAll( replacements );
					if ( keep.isEmpty() ) {
						maybeRemoveImport( JPA + kind );
						maybeRemoveImport( JPA + source );
					}
				}
				return super.visitClassDeclaration( changed ? cd.withLeadingAnnotations( result ) : cd, ctx );
			}

			private String lineBreak() {
				return input.printAll().contains( "\r\n" ) ? "\r\n" : "\n";
			}

			private String indentation(Space space) {
				String whitespace = space.getWhitespace();
				int newline = whitespace.lastIndexOf( '\n' );
				return newline < 0 ? "" : whitespace.substring( newline + 1 );
			}

			private void markEntries(J.Annotation ann) {
				new JavaIsoVisitor<Set<UUID>>() {
					@Override
					public J.@NonNull Annotation visitAnnotation(
							J.@NonNull Annotation a,
							@NonNull Set<UUID> ids) {
						ids.add( a.getId() );
						return super.visitAnnotation( a, ids );
					}
				}.visit( ann, handled );
			}

			private List<Comment> commentsOf(J.Annotation annotation) {
				List<Comment> result = new ArrayList<>();
				new JavaIsoVisitor<List<Comment>>() {
					@Override
					public @NonNull Space visitSpace(
							Space space,
							Space.@NonNull Location loc,
							@NonNull List<Comment> p) {
						p.addAll( space.getComments() );
						return space;
					}
				}.visit( annotation, result );
				return result;
			}

			private Expression containerValue(J.Annotation ann) {
                if ( ann.getArguments() == null || ann.getArguments().size() != 1 ) {
                    return null;
                }
				Expression arg = ann.getArguments().get( 0 );
				if ( arg instanceof J.Assignment ) {
					J.Assignment assignment = (J.Assignment) arg;
					if ( !(assignment.getVariable() instanceof J.Identifier) || !"value".equals(
							((J.Identifier) assignment.getVariable()).getSimpleName() ) ) {
						return null;
					}
					return assignment.getAssignment();
				}
				return arg;
			}

			private List<J.Annotation> entries(J.Annotation ann, String expected) {
				Expression value = containerValue( ann );
				List<? extends Expression> values;
				if ( value instanceof J.Annotation ) {
					values = Collections.singletonList( value );
				}
				else if ( value instanceof J.NewArray ) {
					values = ((J.NewArray) value).getInitializer();
				}
				else {
					return null;
				}
				if ( values == null ) {
					return null;
				}
				List<J.Annotation> entries = new ArrayList<>();
				for ( Expression v : values ) {
					if ( v instanceof J.Empty ) {
						continue;
					}
					if ( !(v instanceof J.Annotation) || !TypeUtils.isOfClassType( v.getType(), JPA + expected ) ) {
						return null;
					}
					entries.add( (J.Annotation) v );
				}
				return entries;
			}

			private J.Annotation rebuild(J.Annotation ann, List<J.Annotation> keep) {
				Expression value = containerValue( ann );
				if ( !(value instanceof J.NewArray) ) {
					return ann;
				}
				J.NewArray array = (J.NewArray) value;
				List<JRightPadded<Expression>> original = array.getPadding().getInitializer().getPadding()
						.getElements();
				List<JRightPadded<Expression>> retained = new ArrayList<>();
				for ( JRightPadded<Expression> rp : original ) {
					if ( keep.contains( rp.getElement() ) ) {
						retained.add( rp );
					}
				}
				// Closing indentation belongs to the array, not to the last removed entry.
				JRightPadded<Expression> last = retained.get( retained.size() - 1 );
				Space closing = original.get( original.size() - 1 ).getAfter();
				if ( !last.getAfter().getComments().isEmpty() ) {
					closing = MigrationSupport.comments( closing.withComments( Collections.emptyList() ),
							last.getAfter() );
				}
				retained.set( retained.size() - 1, last.withAfter( closing ) );
				J.NewArray rebuilt = array.getPadding()
						.withInitializer( array.getPadding().getInitializer().getPadding().withElements( retained ) );
				Expression arg = ann.getArguments().get( 0 );
				return ann.withArguments( Collections.singletonList(
						arg instanceof J.Assignment ? ((J.Assignment) arg).withAssignment( rebuilt ) : rebuilt ) );
			}

			private boolean eligible(J.Annotation ann, boolean nativeQuery, boolean targetContainer, ExecutionContext ctx) {
				String subject = ann.getSimpleName();
				Map<String, J.Assignment> args = new LinkedHashMap<>();
                if ( ann.getArguments() != null ) {
                    for ( Expression expression : ann.getArguments() ) {
                        if ( !(expression instanceof J.Assignment) || !(((J.Assignment) expression).getVariable() instanceof J.Identifier) ) {
                            skip( ann, ctx, subject, "UNSUPPORTED_ANNOTATION_SHAPE",
                                    "Expected explicit name and query members." );
                            return false;
                        }
                        J.Assignment a = (J.Assignment) expression;
                        if ( args.put( ((J.Identifier) a.getVariable()).getSimpleName(), a ) != null ) {
                            skip( ann, ctx, subject, "UNSUPPORTED_ANNOTATION_SHAPE", "Duplicate annotation member." );
                            return false;
                        }
                    }
                }
				if ( !args.containsKey( "name" ) || !args.containsKey( "query" ) ) {
					skip( ann, ctx, subject, "UNSUPPORTED_ANNOTATION_SHAPE",
							"Expected explicit name and query members." );
					return false;
				}
				Expression name = args.get( "name" ).getAssignment();
				if ( name instanceof J.Literal && ((J.Literal) name).getValue() instanceof String ) {
					subject += ": " + ((J.Literal) name).getValue();
				}
				Expression expression = args.get( "query" ).getAssignment();
				if ( !(expression instanceof J.Literal) || !(((J.Literal) expression).getValue() instanceof String) ) {
					skip( ann, ctx, subject, "NON_LITERAL_QUERY",
							"Query expressions and constants require manual review." );
					return false;
				}
				String query = (String) ((J.Literal) expression).getValue();
				String op = QueryOperation.first( query );
				if ( op.equals( "SELECT" ) ) {
					return false;
				}
				if ( !Arrays.asList( "UPDATE", "DELETE", "INSERT" ).contains( op ) ) {
					skip( ann, ctx, subject, "UNSUPPORTED_QUERY_FORM",
							"Expected a literal starting with a complete UPDATE, DELETE, or INSERT keyword." );
					return false;
				}
				List<String> incompatible = new ArrayList<>( args.keySet() );
				incompatible.removeAll( Arrays.asList( "name", "query", "hints" ) );
				if ( !incompatible.isEmpty() ) {
					skip( ann, ctx, subject, "INCOMPATIBLE_ANNOTATION_ATTRIBUTES",
							"Target statement annotation does not support explicit members: " + String.join( ", ",
									incompatible ) );
					return false;
				}
				if ( nativeQuery ) {
					String blocker = QueryOperation.nativeBlocker( query );
					if ( blocker != null ) {
						skip( ann, ctx, subject, blocker,
								"Native SQL requires manual review before treating its result as a row count." );
						return false;
					}
				}
				if ( targetContainer ) {
					skip( ann, ctx, subject, "TARGET_CONTAINER_PRESENT",
							"An explicit target statement container already exists on this declaration." );
					return false;
				}
				return true;
			}

			private J.Annotation convert(J.Annotation ann, String source, String target) {
				JavaType.FullyQualified targetType = JavaType.ShallowClass.build( JPA + target );
				NameTree type = ann.getAnnotationType();
				maybeRemoveImport( JPA + source );
				if ( type instanceof J.FieldAccess ) {
					J.FieldAccess access = (J.FieldAccess) type;
					type = access.withName( access.getName().withSimpleName( target ).withType( targetType ) )
							.withType( targetType );
				}
				else if ( conflictingName( target ) ) {
					type = ((J.FieldAccess) TypeTree.build( JPA + target )).withPrefix( type.getPrefix() )
							.withType( targetType );
				}
				else {
					type = ((J.Identifier) type).withSimpleName( target ).withType( targetType );
					maybeAddImport( JPA + target );
				}
				List<Expression> args = ListUtils.map( ann.getArguments(), expression -> {
					J.Assignment a = (J.Assignment) expression;
					J.Identifier key = (J.Identifier) a.getVariable();
					String newName = key.getSimpleName().equals( "query" ) ? "statement" : key.getSimpleName();
					JavaType keyType = key.getType();
					if ( keyType instanceof JavaType.Method ) {
						keyType = ((JavaType.Method) keyType).withName( newName ).withDeclaringType( targetType );
					}
					return a.withVariable( key.withSimpleName( newName ).withType( keyType ) );
				} );
				return ann.withAnnotationType( type ).withType( targetType ).withArguments( args );
			}

			private boolean conflictingName(String target) {
				for ( J.Import imp : input.getImports() ) {
					String type = imp.getTypeName();
                    if ( type.endsWith( "." + target ) && !type.equals( JPA + target ) ) {
                        return true;
                    }
				}
				final boolean[] conflict = {false};
				new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.TypeParameter visitTypeParameter(J.TypeParameter parameter, Integer p) {
                        if (parameter.getName() instanceof J.Identifier
                                && target.equals(((J.Identifier) parameter.getName()).getSimpleName())) {
                            conflict[0] = true;
                        }
                        return super.visitTypeParameter(parameter, p);
                    }

					@Override
					public J.@NonNull ClassDeclaration visitClassDeclaration(
							J.@NonNull ClassDeclaration cd,
							@NonNull Integer p) {
                        if ( target.equals( cd.getSimpleName() ) ) {
                            conflict[0] = true;
                        }
						return super.visitClassDeclaration( cd, p );
					}
				}.visit( input, 0 );
				return conflict[0];
			}
		};
	}
}
