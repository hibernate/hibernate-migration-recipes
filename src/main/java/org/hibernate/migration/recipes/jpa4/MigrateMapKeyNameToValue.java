package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.support.MigrationSupport;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.java.tree.*;

import java.util.*;

/// Renames an unambiguous JPA MapKey name member without discarding conflicting settings.
///
/// @author Jennifer Joby
/// @author Steve Ebersole
public class MigrateMapKeyNameToValue extends Recipe {
	private final transient SkippedMigrations skipped = new SkippedMigrations( this );

	@Override
	public @NonNull String getDisplayName() {
		return "Replace @MapKey(name=...) with @MapKey(value=...)";
	}

	@Override
	public @NonNull String getDescription() {
		return "Migrates the deprecated MapKey name member to JPA 4 value, preserving conflicts for review.";
	}

	@Override
	public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
		return new MigrationSupport.JavaVisitor( this, skipped ) {
			@Override
			public J.@NonNull Annotation visitAnnotation(
					J.@NonNull Annotation annotation,
					@NonNull ExecutionContext ctx) {
				J.Annotation ann = super.visitAnnotation( annotation, ctx );
                if ( !matches( ann, "jakarta.persistence.MapKey", ctx ) ) {
                    return ann;
                }
				List<Expression> args = ann.getArguments();
                if ( args == null || args.isEmpty() || args.size() == 1 && args.get( 0 ) instanceof J.Empty ) {
                    return ann;
                }
				Set<String> keys = new HashSet<>();
				for ( Expression arg : args ) {
					keys.add( arg instanceof J.Assignment && ((J.Assignment) arg).getVariable() instanceof J.Identifier
							? ((J.Identifier) ((J.Assignment) arg).getVariable()).getSimpleName() : "value" );
				}
				if ( keys.contains( "name" ) && keys.contains( "value" ) ) {
					skip( ann, ctx, "MapKey", "MAP_KEY_CONFLICT",
							"Both name and value are explicit; resolve their precedence manually." );
					return ann;
				}
                if ( args.size() == 1 && keys.contains( "value" ) ) {
                    return ann;
                }
				if ( args.size() != 1 || !keys.contains( "name" ) ) {
					skip( ann, ctx, "MapKey", "UNSUPPORTED_ANNOTATION_SHAPE",
							"Expected a single name or value member." );
					return ann;
				}
				J.Assignment a = (J.Assignment) args.get( 0 );
				Expression value = a.getAssignment();
				Space prefix = MigrationSupport.comments( a.getPrefix(), a.getVariable().getPrefix(),
						a.getPadding().getAssignment().getBefore(), value.getPrefix() );
				return ann.getPadding().withArguments( ann.getPadding().getArguments().getPadding().withElements(
						Collections.singletonList( ann.getPadding().getArguments().getPadding().getElements().get( 0 )
								.withElement( value.withPrefix( prefix ) ) ) ) );
			}
		};
	}
}
