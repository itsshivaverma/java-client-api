/*
 * Copyright 2012 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.client.query;

import com.marklogic.client.io.SearchHandle;
import org.w3c.dom.Document;

/**
 * The SearchResults represent the set of results returned by a search.
 */
public interface SearchResults {
    /**
     * Returns the query definition associated with this query.
     * @return The query definition.
     */
    public QueryDefinition getQueryCriteria();

    /**
     * Returns the total number of results.
     * @return The number of results.
     */
    public long getTotalResults();

    /**
     * Returns the search metrics.
     * @return The metrics.
     */
    public SearchMetrics          getMetrics();

    /**
     * Returns the match results.
     * @return The match results.
     */
    public MatchDocumentSummary[] getMatchResults();

    /**
     * Returns the array of facet results.
     * @return The facet results.
     */
    public FacetResult[]          getFacetResults();

    /**
     * Returns the facet results for the named facet.
     * @param name The facet name.
     * @return The facet results, or null if no facet with the specified name exists.
     */
    public FacetResult            getFacetResult(String name);

    /**
     * Returns the array of facet names returned by this search.
     * @return The array facet names.
     */
    public String[]               getFacetNames();

    /**
     * Returns the query plan.
     * @return The query plan.
     */
    public Document               getPlan();

    /**
     * Returns the array of warnings returned by this search.
     * @return The warnings.
     */
    public SearchHandle.Warning[] getWarnings();

    /**
     * Returns the array of reports returned by this search.
     * @return The reports.
     */
    public SearchHandle.Report[]  getReports();

}

