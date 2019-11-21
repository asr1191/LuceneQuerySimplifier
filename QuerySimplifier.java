import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;

import java.util.HashSet;
import java.util.List;

public class QuerySimplifier {

    private BooleanQuery query;

    QuerySimplifier(BooleanQuery query) {
        this.query = query.clone();
    }

    public void setQuery(BooleanQuery query) {
        this.query = query.clone();
    }

    public BooleanQuery getQuery() {
        return query;
    }

    /**
     * Simplifies the query with which the class has been initialized with and returns the simplified query.
     *
     * @return the simplified query.
     */
    public BooleanQuery simplify() {
        simplifyBooleanQuery(query, null, BooleanClause.Occur.SHOULD, 0);
//        secondPass(query,null,SHOULD)
        return query;
    }


    /**
     * Simplifies {@link BooleanQuery} to use a more efficient query structure by making use of boolean simplifications.
     *
     * @param currentQuery           the root query to start simplification from.
     * @param parentQuery            parent of the root query.
     * @param currentQueryChildOccur {@link BooleanClause.Occur} of the wrapping {@link BooleanClause} of the currentQuery.
     * @param currentIndexInParent   index of currentQuery's {@link BooleanClause} in the parentQuery's list of clauses.
     * @return the change in index position that should be made, after any change to the list of {@link BooleanClause}s.
     */
    private int simplifyBooleanQuery(BooleanQuery currentQuery, BooleanQuery parentQuery, BooleanClause.Occur currentQueryChildOccur, int currentIndexInParent) {
        List<BooleanClause> currentQueryClauses = currentQuery.clauses();
        int indexChanges = 0;
        BooleanClause.Occur childrenOccur = hasHomogenousChildren(currentQuery);
        BooleanClause.Occur currentInParentOccur;
        if (parentQuery != null) {
            currentInParentOccur = parentQuery.clauses().get(currentIndexInParent).getOccur();
        } else {
            currentInParentOccur = BooleanClause.Occur.SHOULD;
        }

        // For-each Loop will result in a ConcurrentModificationException.
        // Iterating through BooleanQueries first before processing other primitive queries.
        for (int currentQueryChildrenClauseIndex = 0; currentQueryChildrenClauseIndex < currentQueryClauses.size(); currentQueryChildrenClauseIndex++) {
            BooleanClause currentQueryChildBooleanClause = currentQueryClauses.get(currentQueryChildrenClauseIndex);
            Query childQuery = currentQueryChildBooleanClause.getQuery();
            if (childQuery instanceof BooleanQuery) {
                currentQueryChildOccur = currentQueryChildBooleanClause.getOccur();
//                if(hasHomogenousChildren((BooleanQuery)childQuery) != null){
                indexChanges = simplifyBooleanQuery((BooleanQuery) childQuery, currentQuery, currentQueryChildOccur, currentQueryChildrenClauseIndex);
//                }
                currentQueryChildrenClauseIndex += indexChanges;
            }
        }

        // Processing non-boolean (primitive) queries.
        for (int currentQueryChildrenClauseIndex = 0; currentQueryChildrenClauseIndex < currentQueryClauses.size(); currentQueryChildrenClauseIndex++) {
            BooleanClause currentQueryBooleanClause = currentQueryClauses.get(currentQueryChildrenClauseIndex);
            Query childQuery = currentQueryBooleanClause.getQuery();
            if (!(childQuery instanceof BooleanQuery)) {
                boolean currentQueryRemoved = removeSingularNesting(currentQuery, currentQueryChildOccur, childQuery, parentQuery, currentQueryChildrenClauseIndex, currentIndexInParent);
            }
        }

        int numberOfDuplicatesRemoved = removeDuplicateChildren(currentQueryClauses);
        indexChanges = replaceQueryWithChildren(childrenOccur, currentInParentOccur, currentQuery, parentQuery, currentQueryClauses, currentIndexInParent);
        return indexChanges;
    }
//    ( f1:hello +f2:world1 ) ( f1:hello +f2:world2 ) ( f1:hello +f2:world3 )

    private int replaceQueryWithChildren(
            BooleanClause.Occur childrenOccur,
            BooleanClause.Occur currentInParentOccur,
            BooleanQuery currentQuery,
            BooleanQuery parentQuery,
            List<BooleanClause> currentQueryClauses,
            int currentIndexInParent
    ) {
        if (childrenOccur != null) { // Checking if the children are indeed homogenous
            if (childrenOccur == currentInParentOccur) {
                if (parentQuery != null) {
                    if (currentQueryClauses.size() > 1) {
                        parentQuery.clauses().remove(new BooleanClause(currentQuery, currentInParentOccur));
                        for (BooleanClause bc :
                                currentQueryClauses) {
                            parentQuery.clauses().add(currentIndexInParent, bc);
                        }
                        System.out.println("Unneccesary wrappings");
                        return currentQueryClauses.size() - 1;
                    }
                }
            }
        }
        return 0;
    }

    private int removeDuplicateChildren(List<BooleanClause> currentQueryClauses) {
        HashSet<BooleanClause> uniqueChildrenClauses = new HashSet<>(currentQueryClauses);
        int numberOfDuplicates = currentQueryClauses.size() - uniqueChildrenClauses.size();
        if (numberOfDuplicates > 0) {
            currentQueryClauses.clear();
            currentQueryClauses.addAll(uniqueChildrenClauses);
        }
        return numberOfDuplicates;
    }

    /**
     * Checks if the given {@link BooleanQuery} has homogenous (all children having the same {@link BooleanClause.Occur}) children.
     *
     * @param currentQuery the query whose children is to be checked for homogenity.
     * @return the {@link BooleanClause.Occur} of the children if they're homogenous, otherwise will return null.
     */
    private BooleanClause.Occur hasHomogenousChildren(BooleanQuery currentQuery) {
        List<BooleanClause> currentQueryClauses = currentQuery.clauses();
        BooleanClause.Occur childrenOccur = currentQueryClauses.get(0).getOccur();
        for (int currentQueryIndex = 0; currentQueryIndex < currentQueryClauses.size(); currentQueryIndex++) {
            if (currentQueryClauses.get(currentQueryIndex).getOccur() != childrenOccur) {
                System.out.println("Heterogenous children");
                return null;
            }
        }
        return childrenOccur;
    }

    /**
     * {@link BooleanQuery}s that have only a single child, is replaced with the child itself.
     *
     * @param currentQuery             query whose child should be removed.
     * @param currentQueryOccur        the {@link BooleanClause.Occur} of the query whose child should be removed.
     * @param childQuery               the child of the query.
     * @param parentQuery              the parent of the query.
     * @param currentQueryClausesindex index of the clause that should be removed.
     * @return true if a singular nesting has been removed.
     */
    private boolean removeSingularNesting(
            BooleanQuery currentQuery,
            BooleanClause.Occur currentQueryOccur,
            Query childQuery,
            BooleanQuery parentQuery,
            int currentQueryClausesindex,
            int currentIndexInParent
    ) {

        if (currentQuery.clauses().size() == 1) {
            if (parentQuery != null) {
                BooleanClause.Occur currentInParentOccur = parentQuery.clauses().get(currentIndexInParent).getOccur();
                if (parentQuery.clauses().remove(new BooleanClause(currentQuery, currentInParentOccur))) {
                    parentQuery.clauses().add(currentQueryClausesindex, new BooleanClause(childQuery, currentInParentOccur));
                    System.out.println("Singular nesting removed");
                } else {
                    System.out.println("COULDNT REMOVE QUERY!");
                }
                return true;
            }
        }
        return false;
    }
}
