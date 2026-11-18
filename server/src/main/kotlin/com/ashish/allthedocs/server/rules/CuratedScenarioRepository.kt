package com.ashish.allthedocs.server.rules

class CuratedScenarioRepository(val scenarios: List<CuratedScenario>) {
    private val byId = scenarios.associateBy { it.id }

    init {
        require(byId.size == scenarios.size) { "Duplicate curated scenario ids" }
        scenarios.forEach { scenario ->
            val ids = scenario.requirements.map { it.requirement.id }
            require(ids.size == ids.toSet().size) { "Duplicate requirement ids in ${scenario.id}" }
        }
    }

    fun find(id: String?): CuratedScenario? = id?.let { byId[it.trim()] }

    fun matchByKeywords(description: String): CuratedScenario? {
        val text = description.lowercase()
        return scenarios
            .map { scenario -> scenario to scenario.keywords.count { keywordMatches(text, it) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun keywordMatches(text: String, keyword: String): Boolean =
        Regex("(^|[^a-z0-9])${Regex.escape(keyword.lowercase())}([^a-z0-9]|$)").containsMatchIn(text)

    companion object {
        val default: CuratedScenarioRepository by lazy { CuratedScenarioRepository(IndiaCuratedScenarios.all) }
    }
}
