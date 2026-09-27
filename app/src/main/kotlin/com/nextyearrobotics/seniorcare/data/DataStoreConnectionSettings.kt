package com.nextyearrobotics.seniorcare.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.nextyearrobotics.seniorcare.network.ConnectionSettings
import com.nextyearrobotics.seniorcare.network.Endpoint
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.connectionDataStore by preferencesDataStore("robot_connection")
class DataStoreConnectionSettings(private val store: DataStore<Preferences>) : ConnectionSettings {
    constructor(context: Context) : this(context.applicationContext.connectionDataStore)
    private val instance = stringPreferencesKey("app_instance_id")
    private val endpoint = stringPreferencesKey("last_endpoint")
    override suspend fun instanceId(): String {
        val values = store.edit { if (it[instance] == null) it[instance] = UUID.randomUUID().toString() }
        return checkNotNull(values[instance])
    }
    override suspend fun lastEndpoint(): String? = store.data.first()[endpoint]?.let { runCatching { Endpoint.validate(it) }.getOrNull() }
    override suspend fun saveEndpoint(endpoint: String) { val valid = Endpoint.validate(endpoint); store.edit { it[this.endpoint] = valid } }
}
