package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.IOUContract
import com.example.state.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*

object TransferIOUFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val stateId: UUID, val newOwner: Party) : FlowLogic<SignedTransaction>() {

        @Suspendable
        override fun call(): SignedTransaction {
            val criteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(stateId))

            val oldState = serviceHub.vaultService.queryBy<IOUState>(criteria).states.single()

            requireThat {
                "Apenas o Lender pode transferir o IOU." using (ourIdentity == oldState.state.data.lender)
            }

            val notary = oldState.state.notary

            val newState = oldState.state.data.copy(lender = newOwner)
            val command = Command(IOUContract.Commands.Transfer(), (newState.participants + ourIdentity).map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(oldState)
                    .addOutputState(newState, oldState.state.contract)
                    .addCommand(command)

            txBuilder.verify(serviceHub)
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            val flowSessions = listOf(newState.lender, newState.borrower).map { initiateFlow(it) }
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, flowSessions))

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
                    val criteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
                    val states = serviceHub.vaultService.queryBy<IOUState>(criteria).states
                    if (states.isNotEmpty()) {
                        val input = states.single().state.data
                        "Eu devo ser o borrower." using (output.borrower == ourIdentity && input.borrower == ourIdentity)
                    }
                    else {
                        "Eu devo ser o novo Lender deste IOU." using (output.lender == ourIdentity)
                    }
                    "Aceito apenas dividas de até 100." using
                            (output.value - output.paymentValue < 100)
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}
