import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// Custom Exceptions
class InsufficientBalanceException extends Exception {
    public InsufficientBalanceException(String message) { super(message); }
}

class InvalidTransactionException extends Exception {
    public InvalidTransactionException(String message) { super(message); }
}

// Immutable Transaction Record
class Transaction {
    private final String transactionId;
    private final String type;
    private final double amount;
    private final String timestamp;

    public Transaction(String type, double amount) {
        this.transactionId = UUID.randomUUID().toString().substring(0, 8);
        this.type = type;
        this.amount = amount;
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public String toString() {
        return String.format("[%s] ID: %s | Type: %-8s | Amount: $%.2f", timestamp, transactionId, type, amount);
    }
}

// Core Bank Account Class (Thread-Safe)
class BankAccount {
    private final String accountNumber;
    private final String accountHolder;
    private double balance;
    private final List<Transaction> transactionHistory;
    private final Lock lock;

    public BankAccount(String accountNumber, String accountHolder, double initialDeposit) {
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
        this.balance = initialDeposit;
        this.transactionHistory = Collections.synchronizedList(new ArrayList<>());
        this.lock = new ReentrantLock();

        transactionHistory.add(new Transaction("INITIAL", initialDeposit));
    }

    public void deposit(double amount) throws InvalidTransactionException {
        if (amount <= 0) {
            throw new InvalidTransactionException("Deposit amount must be strictly positive.");
        }

        lock.lock();
        try {
            balance += amount;
            transactionHistory.add(new Transaction("DEPOSIT", amount));
            logToFile("DEPOSIT", amount);
        } finally {
            lock.unlock();
        }
    }

    public void withdraw(double amount) throws InsufficientBalanceException, InvalidTransactionException {
        if (amount <= 0) {
            throw new InvalidTransactionException("Withdrawal amount must be strictly positive.");
        }

        lock.lock();
        try {
            if (amount > balance) {
                throw new InsufficientBalanceException(
                        String.format("Insufficient funds. Available: $%.2f, Requested: $%.2f", balance, amount)
                );
            }
            balance -= amount;
            transactionHistory.add(new Transaction("WITHDRAW", amount));
            logToFile("WITHDRAW", amount);
        } finally {
            lock.unlock();
        }
    }

    // Deadlock-free transfer using lock ordering
    public void transferTo(BankAccount targetAccount, double amount)
            throws InsufficientBalanceException, InvalidTransactionException {
        if (this == targetAccount) {
            throw new InvalidTransactionException("Cannot transfer money to the same account.");
        }

        // Lock ordering based on hash code to prevent deadlock
        BankAccount firstLock = this.hashCode() < targetAccount.hashCode() ? this : targetAccount;
        BankAccount secondLock = this.hashCode() < targetAccount.hashCode() ? targetAccount : this;

        firstLock.lock.lock();
        secondLock.lock.lock();
        try {
            this.withdraw(amount);
            targetAccount.deposit(amount);
            this.transactionHistory.add(new Transaction("TRANSFER OUT", amount));
            targetAccount.transactionHistory.add(new Transaction("TRANSFER IN", amount));
        } finally {
            secondLock.lock.unlock();
            firstLock.lock.unlock();
        }
    }

    private void logToFile(String type, double amount) {
        try (PrintWriter writer = new PrintWriter(new FileWriter("bank_audit.log", true))) {
            writer.printf("[%s] Account: %s | Action: %s | Amount: $%.2f | New Balance: $%.2f%n",
                    LocalDateTime.now(), accountNumber, type, amount, balance);
        } catch (IOException e) {
            System.err.println("Audit logging failed: " + e.getMessage());
        }
    }

    public String getAccountNumber() { return accountNumber; }
    public String getAccountHolder() { return accountHolder; }
    public double getBalance() { return balance; }

    public void printStatement() {
        System.out.printf("%n=== Account Statement: %s (%s) ===%n", accountNumber, accountHolder);
        synchronized (transactionHistory) {
            for (Transaction t : transactionHistory) {
                System.out.println(t);
            }
        }
        System.out.printf("Current Balance: $%.2f%n================================================%n", balance);
    }
}

// Concurrent Demonstration
public class AdvancedBankingSystem {
    public static void main(String[] args) {
        BankAccount acc1 = new BankAccount("ACC-1001", "Alice", 1500.00);
        BankAccount acc2 = new BankAccount("ACC-1002", "Bob", 500.00);

        // Simulating simultaneous concurrent access using threads
        Thread t1 = new Thread(() -> {
            try {
                acc1.deposit(500);
                acc1.transferTo(acc2, 300);
            } catch (Exception e) {
                System.err.println("Thread 1 Error: " + e.getMessage());
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                acc2.withdraw(150);
                acc1.transferTo(acc2, 200);
            } catch (Exception e) {
                System.err.println("Thread 2 Error: " + e.getMessage());
            }
        });

        t1.start();
        t2.start();

        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Print final statements
        acc1.printStatement();
        acc2.printStatement();
    }
}