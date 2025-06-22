package com.example.cafeteriadashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

// ============== DATA MODELS ==============

class FoodItem {
    private final String name;
    private final AtomicInteger stock;
    private final AtomicInteger sales;
    private final double price;
    private final AtomicLong lastUpdated;

    public FoodItem(String name, int initialStock, double price) {
        this.name = name;
        this.stock = new AtomicInteger(initialStock);
        this.sales = new AtomicInteger(0);
        this.price = price;
        this.lastUpdated = new AtomicLong(System.currentTimeMillis());
    }

    public String getName() { return name; }
    public int getStock() { return stock.get(); }
    public int getSales() { return sales.get(); }
    public double getPrice() { return price; }
    public long getLastUpdated() { return lastUpdated.get(); }

    public boolean decreaseStock(int amount) {
        int current;
        do {
            current = stock.get();
            if (current < amount) return false;
        } while (!stock.compareAndSet(current, current - amount));

        sales.addAndGet(amount);
        lastUpdated.set(System.currentTimeMillis());
        return true;
    }

    public void addStock(int amount) {
        stock.addAndGet(amount);
        lastUpdated.set(System.currentTimeMillis());
    }

    @Override
    public String toString() {
        return String.format("%-15s | Stock: %3d | Sales: %3d | $%.2f",
                name, stock.get(), sales.get(), price);
    }
}

class Sale {
    private final String itemName;
    private final int quantity;
    private final double totalAmount;
    private final LocalDateTime timestamp;
    private final String outlet;

    public Sale(String itemName, int quantity, double price, String outlet) {
        this.itemName = itemName;
        this.quantity = quantity;
        this.totalAmount = quantity * price;
        this.timestamp = LocalDateTime.now();
        this.outlet = outlet;
    }

    // Getters
    public String getItemName() { return itemName; }
    public int getQuantity() { return quantity; }
    public double getTotalAmount() { return totalAmount; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getOutlet() { return outlet; }
}

// ============== THREAD SYNCHRONIZATION INTERFACES ==============

interface CafeteriaLock {
    void lock();
    void unlock();
    boolean tryLock();
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;
}

class CustomReentrantLock implements CafeteriaLock {
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void lock() { lock.lock(); }

    @Override
    public void unlock() { lock.unlock(); }

    @Override
    public boolean tryLock() { return lock.tryLock(); }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return lock.tryLock(time, unit);
    }
}

// ============== RUNNABLE IMPLEMENTATIONS ==============

class InventoryMonitor implements Runnable {
    private final ConcurrentHashMap<String, FoodItem> inventory;
    private final BlockingQueue<String> alerts;
    private final int lowStockThreshold;
    private volatile boolean running = true;
    private final CafeteriaLock lock;

    public InventoryMonitor(ConcurrentHashMap<String, FoodItem> inventory,
                            BlockingQueue<String> alerts,
                            int lowStockThreshold,
                            CafeteriaLock lock) {
        this.inventory = inventory;
        this.alerts = alerts;
        this.lowStockThreshold = lowStockThreshold;
        this.lock = lock;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                lock.lock();
                try {
                    for (FoodItem item : inventory.values()) {
                        if (item.getStock() <= lowStockThreshold) {
                            String alert = String.format("LOW STOCK ALERT: %s has only %d items left!",
                                    item.getName(), item.getStock());
                            alerts.offer(alert);
                        }
                    }
                } finally {
                    lock.unlock();
                }

                Thread.sleep(2000); // Check every 2 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void stop() {
        running = false;
    }
}

class SalesSimulator implements Runnable {
    private final ConcurrentHashMap<String, FoodItem> inventory;
    private final BlockingQueue<Sale> salesQueue;
    private final String outletName;
    private final Random random = new Random();
    private volatile boolean running = true;
    private final CafeteriaLock lock;

    public SalesSimulator(ConcurrentHashMap<String, FoodItem> inventory,
                          BlockingQueue<Sale> salesQueue,
                          String outletName,
                          CafeteriaLock lock) {
        this.inventory = inventory;
        this.salesQueue = salesQueue;
        this.outletName = outletName;
        this.lock = lock;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Simulate random sales
                if (lock.tryLock(500, TimeUnit.MILLISECONDS)) {
                    try {
                        List<String> items = new ArrayList<>(inventory.keySet());
                        if (!items.isEmpty()) {
                            String itemName = items.get(random.nextInt(items.size()));
                            FoodItem item = inventory.get(itemName);
                            int quantity = random.nextInt(3) + 1; // 1-3 items

                            if (item.decreaseStock(quantity)) {
                                Sale sale = new Sale(itemName, quantity, item.getPrice(), outletName);
                                salesQueue.offer(sale);
                            }
                        }
                    } finally {
                        lock.unlock();
                    }
                }

                Thread.sleep(random.nextInt(3000) + 1000); // 1-4 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void stop() {
        running = false;
    }
}

class StockReplenisher implements Runnable {
    private final ConcurrentHashMap<String, FoodItem> inventory;
    private final Random random = new Random();
    private volatile boolean running = true;
    private final CafeteriaLock lock;

    public StockReplenisher(ConcurrentHashMap<String, FoodItem> inventory,
                            CafeteriaLock lock) {
        this.inventory = inventory;
        this.lock = lock;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (lock.tryLock(1, TimeUnit.SECONDS)) {
                    try {
                        for (FoodItem item : inventory.values()) {
                            if (item.getStock() < 10 && random.nextDouble() < 0.3) { // 30% chance to restock
                                int restockAmount = random.nextInt(20) + 10; // 10-30 items
                                item.addStock(restockAmount);
                            }
                        }
                    } finally {
                        lock.unlock();
                    }
                }

                Thread.sleep(5000); // Check every 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void stop() {
        running = false;
    }
}

// ============== PARALLEL PROCESSING SYSTEM ==============

class ParallelSalesAnalyzer {
    private final ExecutorService executor;
    private final ForkJoinPool forkJoinPool;

    public ParallelSalesAnalyzer() {
        this.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        this.forkJoinPool = new ForkJoinPool();
    }

    // Parallel Reduction: Calculate total sales revenue
    public CompletableFuture<Double> calculateTotalRevenue(List<Sale> sales) {
        return CompletableFuture.supplyAsync(() -> {
            return sales.parallelStream()
                    .mapToDouble(Sale::getTotalAmount)
                    .reduce(0.0, Double::sum);
        }, executor);
    }

    // Parallel Decomposition: Analyze sales by outlet
    public CompletableFuture<Map<String, Double>> analyzeSalesByOutlet(List<Sale> sales) {
        return CompletableFuture.supplyAsync(() -> {
            return sales.parallelStream()
                    .collect(Collectors.groupingBy(
                            Sale::getOutlet,
                            Collectors.summingDouble(Sale::getTotalAmount)
                    ));
        }, executor);
    }

    // Parallel Merging: Combine multiple data sources
    public CompletableFuture<Map<String, Integer>> mergeInventoryData(
            List<CompletableFuture<Map<String, Integer>>> futures) {

        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        return allOf.thenApply(v -> {
            Map<String, Integer> merged = new ConcurrentHashMap<>();
            futures.forEach(future -> {
                try {
                    Map<String, Integer> data = future.get();
                    data.forEach((key, value) ->
                            merged.merge(key, value, Integer::sum)
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            return merged;
        });
    }

    // Pipeline Processing: Process sales data through multiple stages
    public CompletableFuture<String> processSalesPipeline(List<Sale> sales) {
        return CompletableFuture.supplyAsync(() -> sales) // Stage 1: Input
                .thenApplyAsync(this::filterRecentSales)        // Stage 2: Filter
                .thenApplyAsync(this::aggregateSales)           // Stage 3: Aggregate
                .thenApplyAsync(this::formatResults);           // Stage 4: Format
    }

    private List<Sale> filterRecentSales(List<Sale> sales) {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        return sales.stream()
                .filter(sale -> sale.getTimestamp().isAfter(oneHourAgo))
                .collect(Collectors.toList());
    }

    private Map<String, Integer> aggregateSales(List<Sale> sales) {
        return sales.stream()
                .collect(Collectors.groupingBy(
                        Sale::getItemName,
                        Collectors.summingInt(Sale::getQuantity)
                ));
    }

    private String formatResults(Map<String, Integer> aggregated) {
        StringBuilder sb = new StringBuilder("Recent Sales Summary:\n");
        aggregated.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> sb.append(String.format("  %s: %d units\n",
                        entry.getKey(), entry.getValue())));
        return sb.toString();
    }

    public void shutdown() {
        executor.shutdown();
        forkJoinPool.shutdown();
    }
}

// ============== LIVENESS PROBLEM DEMONSTRATION ==============

class LivenessDemo {
    private final Object lock1 = new Object();
    private final Object lock2 = new Object();
    private volatile boolean deadlockResolved = false;

    // Potential deadlock scenario
    public void demonstrateDeadlock() {
        Thread t1 = new Thread(() -> {
            synchronized (lock1) {
                System.out.println("Thread 1: Holding lock1...");
                try { Thread.sleep(100); } catch (InterruptedException e) {}

                System.out.println("Thread 1: Waiting for lock2...");
                synchronized (lock2) {
                    System.out.println("Thread 1: Acquired lock2!");
                }
            }
        }, "DeadlockThread1");

        Thread t2 = new Thread(() -> {
            synchronized (lock2) {
                System.out.println("Thread 2: Holding lock2...");
                try { Thread.sleep(100); } catch (InterruptedException e) {}

                System.out.println("Thread 2: Waiting for lock1...");
                synchronized (lock1) {
                    System.out.println("Thread 2: Acquired lock1!");
                }
            }
        }, "DeadlockThread2");

        System.out.println("=== Demonstrating Potential Deadlock ===");
        t1.start();
        t2.start();

        // Timeout mechanism to resolve deadlock
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (t1.isAlive() || t2.isAlive()) {
                    System.out.println("DEADLOCK DETECTED! Interrupting threads...");
                    t1.interrupt();
                    t2.interrupt();
                    deadlockResolved = true;
                }
            }
        }, 2000);

        try {
            t1.join(3000);
            t2.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        timer.cancel();

        if (deadlockResolved) {
            System.out.println("Deadlock resolved by timeout mechanism");
        } else {
            System.out.println("No deadlock occurred - lucky timing!");
        }
    }
}

// ============== PERFORMANCE TESTING ==============

class PerformanceTester {

    public void testConcurrentPerformance(CafeteriaManagementSystem system) {
        System.out.println("\n=== PERFORMANCE TESTING ===");

        // Test 1: Single-threaded vs Multi-threaded sales processing
        System.out.println("\n1. Sales Processing Performance:");
        testSalesProcessing(system);

        // Test 2: Lock contention
        System.out.println("\n2. Lock Contention Test:");
        testLockContention(system);

        // Test 3: Memory usage
        System.out.println("\n3. Memory Usage:");
        testMemoryUsage();
    }

    private void testSalesProcessing(CafeteriaManagementSystem system) {
        int numOperations = 1000;

        // Single-threaded test
        long startTime = System.nanoTime();
        for (int i = 0; i < numOperations; i++) {
            // Simulate processing
            system.getInventory().values().forEach(item -> item.getSales());
        }
        long singleThreadTime = System.nanoTime() - startTime;

        // Multi-threaded test
        startTime = System.nanoTime();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(4);

        for (int i = 0; i < numOperations; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                system.getInventory().values().forEach(item -> item.getSales());
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long multiThreadTime = System.nanoTime() - startTime;

        executor.shutdown();

        System.out.printf("  Single-threaded: %.2f ms\n", singleThreadTime / 1_000_000.0);
        System.out.printf("  Multi-threaded:  %.2f ms\n", multiThreadTime / 1_000_000.0);
        System.out.printf("  Speedup: %.2fx\n", (double) singleThreadTime / multiThreadTime);
    }

    private void testLockContention(CafeteriaManagementSystem system) {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(10);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    if (system.getLock().tryLock(100, TimeUnit.MILLISECONDS)) {
                        try {
                            Thread.sleep(50); // Simulate work
                            successCount.incrementAndGet();
                        } finally {
                            system.getLock().unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        try {
            endLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.printf("  Lock acquisition success rate: %d/10 (%.1f%%)\n",
                successCount.get(), successCount.get() * 10.0);
    }

    private void testMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long beforeMemory = runtime.totalMemory() - runtime.freeMemory();

        // Create many objects to test memory usage
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            sales.add(new Sale("TestItem", 1, 5.0, "TestOutlet"));
        }

        runtime.gc(); // Suggest garbage collection

        long afterMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = afterMemory - beforeMemory;

        System.out.printf("  Memory used for 10,000 sales objects: %.2f MB\n",
                memoryUsed / (1024.0 * 1024.0));

        sales.clear(); // Clean up
    }
}

// ============== MAIN SYSTEM ==============

class CafeteriaManagementSystem {
    private final ConcurrentHashMap<String, FoodItem> inventory;
    private final BlockingQueue<Sale> salesQueue;
    private final BlockingQueue<String> alertQueue;
    private final List<Thread> workerThreads;
    private final List<Runnable> workers;
    private final CafeteriaLock mainLock;
    private final ParallelSalesAnalyzer analyzer;
    private final PerformanceTester performanceTester;

    // For GUI
    private JFrame frame;
    private JTextArea inventoryArea;
    private JTextArea salesArea;
    private JTextArea alertArea;
    private JTextArea analyticsArea;
    private javax.swing.Timer guiUpdateTimer;

    public CafeteriaManagementSystem() {
        this.inventory = new ConcurrentHashMap<>();
        this.salesQueue = new LinkedBlockingQueue<>();
        this.alertQueue = new LinkedBlockingQueue<>();
        this.workerThreads = new ArrayList<>();
        this.workers = new ArrayList<>();
        this.mainLock = new CustomReentrantLock();
        this.analyzer = new ParallelSalesAnalyzer();
        this.performanceTester = new PerformanceTester();

        initializeInventory();
        setupGUI();
        startWorkerThreads();
    }

    private void initializeInventory() {
        inventory.put("Burger", new FoodItem("Burger", 50, 8.99));
        inventory.put("Pizza", new FoodItem("Pizza", 30, 12.50));
        inventory.put("Sandwich", new FoodItem("Sandwich", 40, 6.75));
        inventory.put("Salad", new FoodItem("Salad", 25, 7.99));
        inventory.put("Coffee", new FoodItem("Coffee", 100, 3.50));
        inventory.put("Soda", new FoodItem("Soda", 80, 2.25));
        inventory.put("Pasta", new FoodItem("Pasta", 35, 9.99));
        inventory.put("Soup", new FoodItem("Soup", 20, 5.50));
    }

    private void setupGUI() {
        frame = new JFrame("Real-Time Cafeteria Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new GridLayout(2, 2, 10, 10));
        frame.setSize(1200, 800);

        // Inventory Panel
        JPanel inventoryPanel = new JPanel(new BorderLayout());
        inventoryPanel.setBorder(BorderFactory.createTitledBorder("Live Inventory"));
        inventoryArea = new JTextArea(10, 30);
        inventoryArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        inventoryArea.setEditable(false);
        inventoryPanel.add(new JScrollPane(inventoryArea), BorderLayout.CENTER);

        // Sales Panel
        JPanel salesPanel = new JPanel(new BorderLayout());
        salesPanel.setBorder(BorderFactory.createTitledBorder("Recent Sales"));
        salesArea = new JTextArea(10, 30);
        salesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        salesArea.setEditable(false);
        salesPanel.add(new JScrollPane(salesArea), BorderLayout.CENTER);

        // Alerts Panel
        JPanel alertPanel = new JPanel(new BorderLayout());
        alertPanel.setBorder(BorderFactory.createTitledBorder("System Alerts"));
        alertArea = new JTextArea(10, 30);
        alertArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        alertArea.setEditable(false);
        alertArea.setBackground(new Color(255, 240, 240));
        alertPanel.add(new JScrollPane(alertArea), BorderLayout.CENTER);

        // Analytics Panel
        JPanel analyticsPanel = new JPanel(new BorderLayout());
        analyticsPanel.setBorder(BorderFactory.createTitledBorder("Analytics & Performance"));
        analyticsArea = new JTextArea(10, 30);
        analyticsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        analyticsArea.setEditable(false);
        analyticsPanel.add(new JScrollPane(analyticsArea), BorderLayout.CENTER);

        // Control buttons
        JPanel controlPanel = new JPanel();
        JButton testButton = new JButton("Run Performance Test");
        JButton deadlockButton = new JButton("Demo Liveness Issues");
        JButton resetButton = new JButton("Reset System");

        testButton.addActionListener(e -> {
            new Thread(() -> performanceTester.testConcurrentPerformance(this)).start();
        });

        deadlockButton.addActionListener(e -> {
            new Thread(() -> new LivenessDemo().demonstrateDeadlock()).start();
        });

        resetButton.addActionListener(e -> resetSystem());

        controlPanel.add(testButton);
        controlPanel.add(deadlockButton);
        controlPanel.add(resetButton);
        analyticsPanel.add(controlPanel, BorderLayout.SOUTH);

        frame.add(inventoryPanel);
        frame.add(salesPanel);
        frame.add(alertPanel);
        frame.add(analyticsPanel);

        frame.setVisible(true);

        // Create and start the GUI update timer
        guiUpdateTimer = new javax.swing.Timer(1000, e -> updateGUI());
        guiUpdateTimer.start();
    }

    private void startWorkerThreads() {
        // Inventory monitor
        InventoryMonitor inventoryMonitor = new InventoryMonitor(inventory, alertQueue, 5, mainLock);
        workers.add(inventoryMonitor);
        Thread inventoryThread = new Thread(inventoryMonitor, "InventoryMonitor");
        workerThreads.add(inventoryThread);
        inventoryThread.start();

        // Sales simulators for different outlets
        String[] outlets = {"Main Cafeteria", "Food Court", "Coffee Shop"};
        for (String outlet : outlets) {
            SalesSimulator simulator = new SalesSimulator(inventory, salesQueue, outlet, mainLock);
            workers.add(simulator);
            Thread salesThread = new Thread(simulator, "SalesSimulator-" + outlet);
            workerThreads.add(salesThread);
            salesThread.start();
        }

        // Stock replenisher
        StockReplenisher replenisher = new StockReplenisher(inventory, mainLock);
        workers.add(replenisher);
        Thread replenishThread = new Thread(replenisher, "StockReplenisher");
        workerThreads.add(replenishThread);
        replenishThread.start();

        System.out.println("All worker threads started successfully!");
        System.out.println("Active threads: " + workerThreads.size());
    }

    private void updateGUI() {
        // Update inventory display
        StringBuilder inventoryText = new StringBuilder();
        inventoryText.append("CURRENT INVENTORY STATUS\n");
        inventoryText.append("=" .repeat(50)).append("\n");
        inventory.values().forEach(item -> inventoryText.append(item).append("\n"));
        SwingUtilities.invokeLater(() -> inventoryArea.setText(inventoryText.toString()));

        // Update sales display
        StringBuilder salesText = new StringBuilder();
        salesText.append("RECENT SALES TRANSACTIONS\n");
        salesText.append("=".repeat(50)).append("\n");
        List<Sale> recentSales = new ArrayList<>();
        salesQueue.drainTo(recentSales, 20); // Get up to 20 recent sales

        for (Sale sale : recentSales) {
            salesText.append(String.format("%s | %s | %dx %s | $%.2f\n",
                    sale.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    sale.getOutlet(),
                    sale.getQuantity(),
                    sale.getItemName(),
                    sale.getTotalAmount()));
        }
        SwingUtilities.invokeLater(() -> salesArea.setText(salesText.toString()));

        // Update alerts
        StringBuilder alertText = new StringBuilder();
        alertText.append("SYSTEM ALERTS\n");
        alertText.append("=".repeat(30)).append("\n");
        List<String> alerts = new ArrayList<>();
        alertQueue.drainTo(alerts, 10);

        for (String alert : alerts) {
            alertText.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                    .append(" - ").append(alert).append("\n");
        }
        SwingUtilities.invokeLater(() -> alertArea.setText(alertText.toString()));

        // Update analytics
        updateAnalytics(recentSales);
    }

    private void updateAnalytics(List<Sale> recentSales) {
        StringBuilder analyticsText = new StringBuilder();
        analyticsText.append("REAL-TIME ANALYTICS\n");
        analyticsText.append("=".repeat(40)).append("\n");

        // Thread status
        analyticsText.append("Active Threads: ").append(Thread.activeCount()).append("\n");
        analyticsText.append("Worker Threads: ").append(workerThreads.size()).append("\n\n");

        // Sales analytics using parallel processing
        if (!recentSales.isEmpty()) {
            try {
                CompletableFuture<Double> totalRevenue = analyzer.calculateTotalRevenue(recentSales);
                CompletableFuture<Map<String, Double>> outletAnalysis = analyzer.analyzeSalesByOutlet(recentSales);

                analyticsText.append("Recent Revenue: $").append(String.format("%.2f", totalRevenue.get())).append("\n");

                Map<String, Double> outlets = outletAnalysis.get();
                analyticsText.append("Sales by Outlet:\n");
                outlets.forEach((outlet, revenue) ->
                        analyticsText.append("  ").append(outlet).append(": $").append(String.format("%.2f", revenue)).append("\n"));

            } catch (Exception e) {
                analyticsText.append("Analytics processing...\n");
            }
        }

        // Memory usage
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        analyticsText.append("\nMemory Usage:\n");
        analyticsText.append("  Used: ").append(String.valueOf(usedMemory / (1024 * 1024))).append(" MB\n");
        analyticsText.append("  Free: ").append(String.valueOf(freeMemory / (1024 * 1024))).append(" MB\n");
        analyticsText.append("  Total: ").append(String.valueOf(totalMemory / (1024 * 1024))).append(" MB\n");

        SwingUtilities.invokeLater(() -> analyticsArea.setText(analyticsText.toString()));
    }

    private void resetSystem() {
        // Reset inventory
        initializeInventory();

        // Clear queues
        salesQueue.clear();
        alertQueue.clear();

        System.out.println("System reset completed!");
    }

    // Thread joining demonstration
    public void shutdown() {
        System.out.println("\n=== SHUTTING DOWN SYSTEM ===");

        // Stop all workers
        workers.forEach(worker -> {
            if (worker instanceof InventoryMonitor) {
                ((InventoryMonitor) worker).stop();
            } else if (worker instanceof SalesSimulator) {
                ((SalesSimulator) worker).stop();
            } else if (worker instanceof StockReplenisher) {
                ((StockReplenisher) worker).stop();
            }
        });

        // Stop GUI update timer with the correct Timer type
        if (guiUpdateTimer != null) {
            guiUpdateTimer.stop();
        }

        // Join all worker threads
        for (Thread thread : workerThreads) {
            try {
                thread.join(1000); // Wait up to 1 second for each thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Shutdown the analyzer
        analyzer.shutdown();

        // Close the GUI
        if (frame != null) {
            frame.dispose();
        }

        System.out.println("System shutdown completed!");
    }

    // Getters for testing
    public ConcurrentHashMap<String, FoodItem> getInventory() {
        return inventory;
    }

    public CafeteriaLock getLock() {
        return mainLock;
    }
}

public class CafeteriaDashboard {
    public static void main(String[] args) {
        try {
            // Set look and feel
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Start the management system
        CafeteriaManagementSystem system = new CafeteriaManagementSystem();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown hook activated...");
            system.shutdown();
        }));
    }
}
