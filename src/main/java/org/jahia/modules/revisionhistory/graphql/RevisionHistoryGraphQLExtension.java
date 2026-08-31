package org.jahia.modules.revisionhistory.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;

/**
 * Attaches this module's query and mutation namespaces to the root types.
 *
 * <p>Registration is by bundle scan: {@code @GraphQLTypeExtension} on a class in this bundle is
 * enough, so there is no wiring to forget and nothing to keep in sync.
 *
 * <p>ONE field on each root type, returning a container. The namespace comes from the container's
 * RETURN TYPE, not from {@code @GraphQLName}, which is cosmetic. Adding fields directly to Query or
 * Mutation is what must not happen: two bundles registering the same global field make
 * DXGraphQLProvider refuse the duplicate and the entire schema fails to build -- every module's
 * fields, not just the colliding ones.
 */
public class RevisionHistoryGraphQLExtension {

    @GraphQLTypeExtension(DXGraphQLProvider.Query.class)
    @GraphQLDescription("Content Revision History")
    public static class Query {

        private Query() {
        }

        @GraphQLField
        @GraphQLName("contentRevisionHistory")
        @GraphQLDescription("Content Revision History queries")
        public static RevisionHistoryQuery getContentRevisionHistory() {
            return new RevisionHistoryQuery();
        }
    }

    @GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
    @GraphQLDescription("Content Revision History")
    public static class Mutation {

        private Mutation() {
        }

        @GraphQLField
        @GraphQLName("contentRevisionHistory")
        @GraphQLDescription("Content Revision History mutations")
        public static RevisionHistoryMutation getContentRevisionHistory() {
            return new RevisionHistoryMutation();
        }
    }
}
