package com.example.contract

import com.example.state.IOUState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

/**
 * A implementation of a basic smart contract in Corda.
 *
 * This contract enforces rules regarding the creation of a valid [IOUState], which in turn encapsulates an [IOU].
 *
 * For a new [IOU] to be issued onto the ledger, a transaction is required which takes:
 * - Zero input states.
 * - One output state: the new [IOU].
 * - An Create() command with the public keys of both the lender and the borrower.
 *
 * All contracts must sub-class the [Contract] interface.
 */
open class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "com.example.contract.IOUContract"
    }

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        when(command.value) {
            is Commands.Create -> verifyCreate(tx)
            is Commands.Pay -> verifyPay(tx)
        }
    }

    private fun verifyCreate(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        requireThat {
            // Generic constraints around the IOU transaction.
            "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val out = tx.outputsOfType<IOUState>().single()
            "The lender and the borrower cannot be the same entity." using (out.lender != out.borrower)
            "All of the participants must be signers." using (command.signers.containsAll(out.participants.map { it.owningKey }))

            // IOU-specific constraints.
            "The IOU's value must be non-negative." using (out.value > 0)
            "The IOU's payment value must be 0." using (out.paymentValue == 0)
        }
    }

    private fun verifyPay(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        requireThat {
            // Generic constraints around the IOU transaction.
            "Only one input should be consumed when paying an IOU." using (tx.inputs.size == 1)
            "Only one output state should be created." using (tx.outputs.size == 1)
            val input = tx.inputsOfType<IOUState>().single()
            val output = tx.outputsOfType<IOUState>().single()
            "The input and output state should be the same." using (input.linearId == output.linearId)
            "All of the participants must be signers." using (command.signers.containsAll(output.participants.map { it.owningKey }))

            // IOU-specific constraints.
            "O valor de pagamento no Output tem que ser maior que o valor de pagamento no Input." using
                    (output.paymentValue > input.paymentValue)
            "O valor de pagamento no Output não pode ser maior que o valor do empréstimo." using
                    (output.paymentValue <= output.value)
            "Apenas o valor de pagamento pode ser alterado." using
                    (input.lender == output.lender &&
                            input.borrower == output.borrower &&
                            input.value == output.value)
        }

    }

    /**
     * This contract only implements one command, Create.
     */
    interface Commands : CommandData {
        class Create : Commands
        class Pay: Commands
    }
}
