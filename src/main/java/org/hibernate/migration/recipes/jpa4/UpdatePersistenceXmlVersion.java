package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.support.MigrationSupport;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.internal.XmlPrinter;
import org.openrewrite.xml.tree.Xml;

import java.util.*;
import java.util.regex.*;

/// Upgrades recognized Jakarta persistence descriptors without normalizing unrelated XML.
///
/// @author Jennifer Joby
/// @author Steve Ebersole
public class UpdatePersistenceXmlVersion extends Recipe {
	private static final String NS = "https://jakarta.ee/xml/ns/persistence";
	private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

	private final transient SkippedMigrations skipped = new SkippedMigrations( this );

	@Override
	public @NonNull String getDisplayName() {
		return "Update persistence.xml to JPA 4.0";
	}

	@Override
	public @NonNull String getDescription() {
		return "Upgrades supported Jakarta Persistence 3.x descriptors, preserving unrelated schema references and conflicts.";
	}

	@Override
	public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
		return new XmlIsoVisitor<ExecutionContext>() {
			@Override
			public Xml.@NonNull Document visitDocument(Xml.@NonNull Document doc, @NonNull ExecutionContext ctx) {
				Xml.Tag root = doc.getRoot();
				String[] name = root.getName().split( ":", -1 );
                if ( !"persistence".equals( name[name.length - 1] ) ) {
                    return doc;
                }
				Map<String, String> ns = new HashMap<>();
				for ( Xml.Attribute a : root.getAttributes() ) {
                    if ( "xmlns".equals( a.getKeyAsString() ) ) {
                        ns.put( "", a.getValueAsString() );
                    }
                    else if ( a.getKeyAsString().startsWith( "xmlns:" ) ) {
                        ns.put( a.getKeyAsString().substring( 6 ), a.getValueAsString() );
                    }
				}
				if ( name.length > 2 || !NS.equals( ns.get( name.length == 1 ? "" : name[0] ) ) ) {
					return skip( doc, ctx, "XML_NAMESPACE_UNSUPPORTED",
							"The persistence root must declare the Jakarta Persistence namespace." );
				}
				Xml.Attribute version = null, schema = null;
				Set<String> expanded = new HashSet<>();
				String schemaProblem = null;
				for ( Xml.Attribute a : root.getAttributes() ) {
					String key = a.getKeyAsString();
                    if ( key.equals( "xmlns" ) || key.startsWith( "xmlns:" ) ) {
                        continue;
                    }
					String[] parts = key.split( ":", -1 );
					String local = parts[parts.length - 1];
					String uri = parts.length == 1 ? "" : ns.get( parts[0] );
                    if ( parts.length > 2 || uri == null || !expanded.add( "{" + uri + "}" + local ) ) {
                        schemaProblem = "Duplicate attributes or unresolved namespace prefixes.";
                    }
                    if ( key.equals( "version" ) ) {
                        version = a;
                    }
					if ( "schemaLocation".equals( local ) ) {
                        if ( XSI.equals( uri ) ) {
                            schema = a;
                        }
                        else if ( uri == null || uri.isEmpty() ) {
                            schemaProblem = "The schemaLocation attribute has no schema-instance namespace binding.";
                        }
					}
                    if ( "noNamespaceSchemaLocation".equals( local ) && XSI.equals( uri ) ) {
                        schemaProblem = "A namespaced descriptor cannot use noNamespaceSchemaLocation.";
                    }
				}
				String v = version == null ? "" : version.getValueAsString();
                if ( !Arrays.asList( "3.0", "3.1", "3.2", "4.0" ).contains( v ) ) {
                    return skip( doc, ctx, "XML_VERSION_UNSUPPORTED",
                            "Unsupported or missing persistence version: " + v );
                }
                if ( schemaProblem != null ) {
                    return skip( doc, ctx, "XML_SCHEMA_CONFLICT", schemaProblem );
                }
				String replacement = null;
				if ( schema != null ) {
					String value = schema.getValueAsString();
					Matcher tokens = Pattern.compile( "\\S+" ).matcher( value );
					List<String> words = new ArrayList<>();
					List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
					while ( tokens.find() ) {
						words.add( tokens.group() );
						starts.add( tokens.start() );
						ends.add( tokens.end() );
					}
                    if ( words.size() % 2 != 0 ) {
                        return skip( doc, ctx, "XML_SCHEMA_CONFLICT",
                                "schemaLocation must contain namespace/location pairs." );
                    }
					boolean found = false;
					for ( int i = 0; i < words.size(); i += 2 ) {
                        if ( !NS.equals( words.get( i ) ) ) {
                            continue;
                        }
                        if ( found ) {
                            return skip( doc, ctx, "XML_SCHEMA_CONFLICT", "Duplicate persistence schema pairs." );
                        }
						found = true;
						String expected = NS + "/persistence_" + v.replace( '.', '_' ) + ".xsd";
                        if ( !expected.equals( words.get( i + 1 ) ) ) {
                            return skip( doc, ctx, "XML_SCHEMA_LOCATION_UNSUPPORTED",
                                    "The persistence schema location does not match the declared version's canonical schema." );
                        }
						replacement = value.substring( 0,
								starts.get( i + 1 ) ) + NS + "/persistence_4_0.xsd" + value.substring(
								ends.get( i + 1 ) );
					}
				}
                if ( "4.0".equals( v ) ) {
                    return doc;
                }
				List<Xml.Attribute> attrs = new ArrayList<>();
				for ( Xml.Attribute a : root.getAttributes() ) {
                    if ( a == version ) {
                        attrs.add( a.withValue( a.getValue().withValue( "4.0" ) ) );
                    }
                    else if ( a == schema && replacement != null ) {
                        attrs.add( a.withValue( a.getValue().withValue( replacement ) ) );
                    }
                    else {
                        attrs.add( a );
                    }
				}
				return doc.withRoot( root.withAttributes( attrs ) );
			}

			private Xml.Document skip(Xml.Document doc, ExecutionContext ctx, String reason, String message) {
				final int[] offset = {-1};
				PrintOutputCapture<Integer> out = new PrintOutputCapture<>( 0 );
				new XmlPrinter<Integer>() {
					@Override
					public @NonNull Xml visitTag(Xml.@NonNull Tag tag, @NonNull PrintOutputCapture<Integer> p) {
                        if ( tag.getId().equals( doc.getRoot().getId() ) ) {
                            offset[0] = p.getOut().length() + tag.getPrefix().length();
                        }
						return super.visitTag( tag, p );
					}
				}.visit( doc, out );
				MigrationSupport.report( skipped, UpdatePersistenceXmlVersion.this, getCursor(), ctx, doc,
						doc.getRoot(),
						MigrationSupport.position( out.getOut(), offset[0] ), "persistence", reason, message );
				return doc;
			}
		};
	}
}
