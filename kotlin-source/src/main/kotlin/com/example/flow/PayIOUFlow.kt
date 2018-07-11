package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.IOUContract
import com.example.state.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*

object PayIOUFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val stateId: UUID, val paymentValue: Int) : FlowLogic<SignedTransaction>() {

        @Suspendable
        override fun call(): SignedTransaction {
            val criteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(stateId))

            val oldState = serviceHub.vaultService.queryBy<IOUState>(criteria).states.single()

            requireThat {
                "Apenas o Borrower pode pagar o IOU." using (ourIdentity == oldState.state.data.borrower)
            }

            val notary = oldState.state.notary

            val newState = oldState.state.data.copy(paymentValue = paymentValue+oldState.state.data.paymentValue)
            val command = Command(IOUContract.Commands.Pay(), newState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(oldState)
                    .addOutputState(newState, oldState.state.contract)
                    .addCommand(command)

            txBuilder.verify(serviceHub)
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            val flowSession = initiateFlow(newState.lender)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(flowSession)))

            return subFlow(FinalityFlow(fullySignedTx))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.coreTransaction.outputsOfType<IOUState>().single()
                    "Eu devo ser o Lender deste IOU." using (output.lender == ourIdentity)
                    "O pagamentos parciais precisam ser de no mínimo a metade da dívida." using
                            (output.paymentValue == output.value || output.paymentValue >= (output.value / 2))
                    val criteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
                    val input = serviceHub.vaultService.queryBy<IOUState>(criteria).states.single().state.data
                    "O pagamento total deve ser realizado." using
                            (output.paymentValue == output.value || input.paymentValue == 0)
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}
