package org.jahia.modules.revisionhistory.graphql;

import org.jahia.modules.graphql.provider.dxm.DXGraphQLExtensionsProvider;
import org.osgi.service.component.annotations.Component;

import java.util.Arrays;
import java.util.Collection;

/**
 * Declares this module's schema extensions to the platform's GraphQL provider.
 *
 * <p>{@code @GraphQLTypeExtension} alone does nothing: the provider does not scan bundles for it.
 * Extensions are discovered as an OSGi service implementing
 * {@link DXGraphQLExtensionsProvider}, which is how every shipped module that extends the schema
 * does it -- {@code security-filter-tools} and {@code tools} among them. Without this component the
 * annotated classes compile, deploy, and are simply never registered, and the only symptom is a
 * field that is not in the schema.
 */
@Component(service = DXGraphQLExtensionsProvider.class, immediate = true)
public class RevisionHistoryGraphQLProvider implements DXGraphQLExtensionsProvider {

    @Override
    public Collection<Class<?>> getExtensions() {
        return Arrays.asList(
                RevisionHistoryGraphQLExtension.Query.class,
                RevisionHistoryGraphQLExtension.Mutation.class);
    }
}
