package com.ashish.allthedocs.di

import com.ashish.allthedocs.cloud.CloudAuthManager
import com.ashish.allthedocs.cloud.CloudSearchService
import com.ashish.allthedocs.cloud.mcp.McpConnectorManager
import com.ashish.allthedocs.cloud.mcp.McpSearchService
import com.ashish.allthedocs.data.ApiClient
import com.ashish.allthedocs.ui.cloud.CloudSearchViewModel
import com.ashish.allthedocs.data.ScenarioRepository
import com.ashish.allthedocs.data.SettingsRepository
import com.ashish.allthedocs.data.VaultRepository
import com.ashish.allthedocs.data.db.AppDatabase
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.platform.IncomingDocuments
import com.ashish.allthedocs.ui.scenario.NewScenarioViewModel
import com.ashish.allthedocs.ui.scenario.ScenarioDetailViewModel
import com.ashish.allthedocs.ui.scenario.ScenarioListViewModel
import com.ashish.allthedocs.ui.settings.SettingsViewModel
import com.ashish.allthedocs.ui.vault.AddDocumentViewModel
import com.ashish.allthedocs.ui.vault.VaultDocumentViewModel
import com.ashish.allthedocs.ui.vault.VaultListViewModel
import io.ktor.client.engine.HttpClientEngine
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val commonModule = module {
    single { get<AppDatabase>().scenarioDao() }
    single { get<AppDatabase>().scenarioDraftDao() }
    single { get<AppDatabase>().vaultDocumentDao() }
    single { get<AppDatabase>().settingDao() }
    single { ApiClient.createHttpClient(get<HttpClientEngine>()) }
    single { SettingsRepository(get()) }
    single { IncomingDocuments() }
    single {
        val settings = get<SettingsRepository>()
        ApiClient(get()) { settings.serverUrl() }
    }
    single { ScenarioRepository(get(), get(), get()) }
    single { CloudAuthManager(get(), get(), get()) }
    single { CloudSearchService(get(), get<CloudAuthManager>()) }
    single { McpConnectorManager(get(), get(), get()) }
    single { McpSearchService(get(), get<McpConnectorManager>()) }
    single { VaultRepository(get(), get(), get(), get(), get(), get()) }

    viewModelOf(::ScenarioListViewModel)
    viewModel { (draftId: String, curatedScenarioId: String) ->
        NewScenarioViewModel(draftId, curatedScenarioId.ifBlank { null }, get())
    }
    viewModel { (id: String) -> ScenarioDetailViewModel(id, get(), get()) }
    viewModelOf(::VaultListViewModel)
    viewModel { (id: String) -> VaultDocumentViewModel(id, get()) }
    viewModel { (expectedTypes: List<DocumentType>, suggestedTitle: String, incoming: Boolean) ->
        AddDocumentViewModel(expectedTypes, suggestedTitle, incoming, get(), get())
    }
    viewModelOf(::SettingsViewModel)
    viewModel { (expectedTypes: List<DocumentType>) -> CloudSearchViewModel(expectedTypes, get(), get(), get(), get(), get()) }
}

fun initKoin(platformModule: Module, appDeclaration: KoinAppDeclaration = {}): KoinApplication = startKoin {
    appDeclaration()
    modules(platformModule, commonModule)
}
