package com.luum.michi.app.core

import com.luum.michi.app.core.network.domain.graphQLRequestCost
import kotlin.test.Test
import kotlin.test.assertEquals

/** AniList charges each top-level field; the count must match. */
class GraphQLRequestCostTest {

    @Test
    fun oneRootFieldCostsOne() {
        assertEquals(1, graphQLRequestCost("query Viewer { Viewer { id name } }"))
    }

    @Test
    fun everyAliasedPageCountsOnce() {
        val query = """
            query Dashboard(${'$'}season: MediaSeason!, ${'$'}isAdult: Boolean) {
              seasonal: Page(perPage: 12) {
                media(type: ANIME, season: ${'$'}season, isAdult: ${'$'}isAdult) { id title { romaji } }
              }
              trending: Page(perPage: 12) { media(sort: TRENDING_DESC) { id } }
              popular : Page(perPage: 12) { media(sort: POPULARITY_DESC) { id } }
            }
        """
        assertEquals(3, graphQLRequestCost(query))
    }

    @Test
    fun profileQueryCountsUserViewerAndBothPages() {
        val query = """
            query UserProfile(${'$'}userId: Int!) {
              User(id: ${'$'}userId) { id favourites { anime { nodes { id } } } }
              Viewer { id }
              followers: Page(perPage: 1) { pageInfo { total } }
              following: Page(perPage: 1) { pageInfo { total } }
            }
        """
        assertEquals(4, graphQLRequestCost(query))
    }

    @Test
    fun stringsCommentsAndFragmentsDoNotAddFields() {
        val query = """
            # a comment { with braces }
            query Search {
              Page(perPage: 5) { media(search: "a { b: c }") { ...Fields } }
              ... on Query { SiteStatistics { anime { nodes { count } } } }
            }
            fragment Fields on Media { id title { romaji } }
        """
        assertEquals(2, graphQLRequestCost(query))
    }

    @Test
    fun neverBelowOne() {
        assertEquals(1, graphQLRequestCost(""))
    }
}
