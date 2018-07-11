package com.example.flow

import com.example.state.IOUState
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.node.services.queryBy
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IOUFlowTests {
    lateinit var network: MockNetwork
    lateinit var a: StartedMockNode
    lateinit var b: StartedMockNode
    lateinit var c: StartedMockNode

    @Before
    fun setup() {
        network = MockNetwork(listOf("com.example.contract"))
        a = network.createPartyNode()
        b = network.createPartyNode()
        c = network.createPartyNode()
        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b, c).forEach {
            it.registerInitiatedFlow(ExampleFlow.Acceptor::class.java)
            it.registerInitiatedFlow(PayIOUFlow.Acceptor::class.java)
            it.registerInitiatedFlow(TransferIOUFlow.Acceptor::class.java)
        }
        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `flow rejects invalid IOUs`() {
        val flow = ExampleFlow.Initiator(-1, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        // The IOUContract specifies that IOUs cannot have negative values.
        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the initiator`() {
        val flow = ExampleFlow.Initiator(1, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(b.info.singleIdentity().owningKey)
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the acceptor`() {
        val flow = ExampleFlow.Initiator(1, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(a.info.singleIdentity().owningKey)
    }

    @Test
    fun `flow records a transaction in both parties' transaction storages`() {
        val flow = ExampleFlow.Initiator(1, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(a, b)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `recorded transaction has no inputs and a single output, the input IOU`() {
        val iouValue = 1
        val flow = ExampleFlow.Initiator(iouValue, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both vaults.
        for (node in listOf(a, b)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            val txOutputs = recordedTx!!.tx.outputs
            assert(txOutputs.size == 1)

            val recordedState = txOutputs[0].data as IOUState
            assertEquals(recordedState.value, iouValue)
            assertEquals(recordedState.lender, a.info.singleIdentity())
            assertEquals(recordedState.borrower, b.info.singleIdentity())
        }
    }

    @Test
    fun `flow records the correct IOU in both parties' vaults`() {
        val iouValue = 1
        val flow = ExampleFlow.Initiator(1, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        future.getOrThrow()

        // We check the recorded IOU in both vaults.
        for (node in listOf(a, b)) {
            node.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, iouValue)
                assertEquals(recordedState.lender, a.info.singleIdentity())
                assertEquals(recordedState.borrower, b.info.singleIdentity())
            }
        }
    }

    @Test
    fun `deve ser possivel realizar um pagamento parcial de ao menos metade do valor`() {
        val flow = ExampleFlow.Initiator(10, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val stateCriado = future.getOrThrow().coreTransaction.outputsOfType<IOUState>().single()

        val payFlow = PayIOUFlow.Initiator(stateCriado.linearId.id, 5)
        val payFuture = b.startFlow(payFlow)
        network.runNetwork()
        payFuture.getOrThrow()

        for (node in listOf(a, b)) {
            node.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, 10)
                assertEquals(recordedState.paymentValue, 5)
                assertEquals(recordedState.lender, a.info.singleIdentity())
                assertEquals(recordedState.borrower, b.info.singleIdentity())
            }
        }
    }

    fun `deve ser possivel realizar um pagamento parcial e depois realizar o pagamento total`() {
        val flow = ExampleFlow.Initiator(10, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val stateCriado = future.getOrThrow().coreTransaction.outputsOfType<IOUState>().single()

        val payFlow = PayIOUFlow.Initiator(stateCriado.linearId.id, 5)
        val payFuture = b.startFlow(payFlow)
        network.runNetwork()
        payFuture.getOrThrow()

        val payFlow2 = PayIOUFlow.Initiator(stateCriado.linearId.id, 5)
        val payFuture2 = b.startFlow(payFlow2)
        network.runNetwork()
        payFuture2.getOrThrow()

        for (node in listOf(a, b)) {
            node.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, 10)
                assertEquals(recordedState.paymentValue, 10)
                assertEquals(recordedState.lender, a.info.singleIdentity())
                assertEquals(recordedState.borrower, b.info.singleIdentity())
            }
        }
    }

    fun `não deve ser possível realizar dois pagamentos parciais`() {
        val flow = ExampleFlow.Initiator(10, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val stateCriado = future.getOrThrow().coreTransaction.outputsOfType<IOUState>().single()

        val payFlow = PayIOUFlow.Initiator(stateCriado.linearId.id, 5)
        val payFuture = b.startFlow(payFlow)
        network.runNetwork()
        payFuture.getOrThrow()

        val payFlow2 = PayIOUFlow.Initiator(stateCriado.linearId.id, 2)
        val payFuture2 = b.startFlow(payFlow2)
        network.runNetwork()
        assertFailsWith<TransactionVerificationException> { payFuture2.getOrThrow() }

        for (node in listOf(a, b)) {
            node.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, 10)
                assertEquals(recordedState.paymentValue, 5)
                assertEquals(recordedState.lender, a.info.singleIdentity())
                assertEquals(recordedState.borrower, b.info.singleIdentity())
            }
        }
    }

    @Test
    fun `deve ser possivel executar a funcao de pagamento`() {
        val flow = ExampleFlow.Initiator(10, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val stateCriado = future.getOrThrow().coreTransaction.outputsOfType<IOUState>().single()

        val payFlow = PayIOUFlow.Initiator(stateCriado.linearId.id, 10)
        val payFuture = b.startFlow(payFlow)
        network.runNetwork()
        payFuture.getOrThrow()

        for (node in listOf(a, b)) {
            node.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(10, recordedState.value)
                assertEquals(10, recordedState.paymentValue)
                assertEquals(recordedState.lender, a.info.singleIdentity())
                assertEquals(recordedState.borrower, b.info.singleIdentity())
            }
        }
    }

    @Test
    fun `deve ser possivel executar a funcao de transferencia`() {
        val flow = ExampleFlow.Initiator(10, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val stateCriado = future.getOrThrow().coreTransaction.outputsOfType<IOUState>().single()

        val payFlow = TransferIOUFlow.Initiator(stateCriado.linearId.id, c.info.singleIdentity())
        val payFuture = a.startFlow(payFlow)
        network.runNetwork()
        payFuture.getOrThrow()

        for (node in listOf(b, c)) {
            node.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(10, recordedState.value)
                assertEquals(0, recordedState.paymentValue)
                assertEquals(recordedState.lender, c.info.singleIdentity())
                assertEquals(recordedState.borrower, b.info.singleIdentity())
            }
        }
        a.transaction {
            val ious = a.services.vaultService.queryBy<IOUState>().states
            assertEquals(0, ious.size)
        }
    }
}