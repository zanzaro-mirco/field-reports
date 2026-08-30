package it.mircozanzaro.fieldreports

import it.mircozanzaro.fieldreports.data.local.InMemoryReportsLocalStore
import it.mircozanzaro.fieldreports.data.local.ReportsLocalStore

/** Il contratto verificato sullo store in memoria, quello usato dai test. */
class InMemoryReportsLocalStoreTest : ReportsLocalStoreContract() {
    override fun createStore(): ReportsLocalStore = InMemoryReportsLocalStore()
}
