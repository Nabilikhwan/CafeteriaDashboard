package com.example.cafeteriadashboard;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.logging.Logger;
import java.util.stream.IntStream;

// Main Application Class
public class CafeteriaDashboard extends JFrame {
    private final CafeteriaSystem cafeteriaSystem;

    public CafeteriaDashboard() {
        cafeteriaSystem = new CafeteriaSystem();
        DashboardUI dashboardUI = new DashboardUI(cafeteriaSystem);

        setTitle("Real-Time Cafeteria Analytics Dashboard");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        add(dashboardUI, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        // Start the cafeteria system
        cafeteriaSystem.startSystem();

        // Add window listener to handle system shutdown
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                cafeteriaSystem.stopSystem();
                dispose();
            }
        });
    }

    public static void main() {
        SwingUtilities.invokeLater(CafeteriaDashboard::new);
    }
}

// Core System Classes
class CafeteriaSystem {
    private final OrderQueue orderQueue;
    private final KitchenStaff kitchenStaff;
    private final InventoryManager inventoryManager;
    private final MetricsCollector metricsCollector;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutor;
    private volatile boolean systemRunning = false;

    public CafeteriaSystem() {
        orderQueue = new OrderQueue();
        inventoryManager = new InventoryManager();
        kitchenStaff = new KitchenStaff(5, orderQueue); // Pass 'this' to KitchenStaff
        metricsCollector = new MetricsCollector();
        executorService = Executors.newCachedThreadPool();
        scheduledExecutor = Executors.newScheduledThreadPool(3);
    }

    public void startSystem() {
        systemRunning = true;
        kitchenStaff.startWorkers();

        // Only keep metrics update task, remove order generator
        scheduledExecutor.scheduleAtFixedRate(
                new MetricsUpdateTask(metricsCollector, orderQueue), 0, 2, TimeUnit.SECONDS);
    }

    public void stopSystem() {
        systemRunning = false;
        kitchenStaff.shutdown();
        executorService.shutdown();
        scheduledExecutor.shutdown();

        try {
            boolean executorTerminated = executorService.awaitTermination(2, TimeUnit.SECONDS);
            boolean schedulerTerminated = scheduledExecutor.awaitTermination(2, TimeUnit.SECONDS);

            if (!executorTerminated || !schedulerTerminated) {
                Logger.getLogger(getClass().getName()).warning("Some tasks did not terminate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.getLogger(getClass().getName()).warning("System shutdown interrupted");
        }
    }

    // Getters for UI access
    public OrderQueue getOrderQueue() { return orderQueue; }
    public KitchenStaff getKitchenStaff() { return kitchenStaff; }
    public InventoryManager getInventoryManager() { return inventoryManager; }
    public MetricsCollector getMetricsCollector() { return metricsCollector; }
    public boolean isSystemRunning() { return systemRunning; }

    public boolean processOrderIngredients(String itemName) {
        InventoryManager inventory = getInventoryManager();
        return switch (itemName) {
            case "Chicken Burger" -> inventory.consumeIngredient("Burger Buns", 1) &&
                    inventory.consumeIngredient("Chicken Breast", 1);
            case "Pizza" -> inventory.consumeIngredient("Pizza", 1);  // Changed from Pizza Dough
            case "Pasta" -> inventory.consumeIngredient("Pasta", 1);  // Changed from Pasta Dish
            case "Salad Mix" -> inventory.consumeIngredient("Salad Mix", 1);  // Changed from Fresh Salad
            default -> false;
        };
    }
}

// Order Management with Thread-Safe Queue
class OrderQueue {
    private final BlockingQueue<Order> pendingOrders = new LinkedBlockingQueue<>();
    private final List<Order> completedOrders = Collections.synchronizedList(new ArrayList<>());
    private final Set<Order> activeOrders = Collections.synchronizedSet(new HashSet<>());
    private final ReentrantLock queueLock = new ReentrantLock();
    private final Condition orderAvailable = queueLock.newCondition();
    private final AtomicInteger orderIdCounter = new AtomicInteger(1);

    public void addOrder(Order order) {
        queueLock.lock();
        try {
            order.setId(orderIdCounter.getAndIncrement());
            order.setOrderTime(System.currentTimeMillis());
            pendingOrders.add(order);
            orderAvailable.signalAll();
        } finally {
            queueLock.unlock();
        }
    }

    public Order takeOrder() throws InterruptedException {
        queueLock.lock();
        try {
            while (pendingOrders.isEmpty()) {
                orderAvailable.await();
            }

            // Take the first order that isn't already being processed
            for (Order order : pendingOrders) {
                if (!activeOrders.contains(order)) {
                    activeOrders.add(order);  // Mark as being processed
                    return order;  // Return this order to the worker
                }
            }

            // If all orders are being processed, wait
            orderAvailable.await();
            return null;
        } finally {
            queueLock.unlock();
        }
    }

    public void completeOrder(Order order) {
        queueLock.lock();
        try {
            // Ensure minimum processing time of 2 seconds
            long timeInPending = System.currentTimeMillis() - order.getOrderTime();
            if (timeInPending < 2000) {
                try {
                    Thread.sleep(2000 - timeInPending);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Remove from both collections
            if (activeOrders.remove(order) && pendingOrders.remove(order)) {
                order.setCompletionTime(System.currentTimeMillis());
                completedOrders.add(order);
                orderAvailable.signalAll(); // Signal that an order was completed
            }
        } finally {
            queueLock.unlock();
        }
    }

    public List<Order> getCompletedOrders() {
        synchronized(completedOrders) {
            return new ArrayList<>(completedOrders);
        }
    }

    public List<Order> getPendingOrders() {
        queueLock.lock();
        try {
            return new ArrayList<>(pendingOrders);
        } finally {
            queueLock.unlock();
        }
    }
}

// Kitchen Staff with Worker Threads
class KitchenStaff {
    private final List<KitchenWorker> workers;
    private final ExecutorService workerPool;

    public KitchenStaff(int workerCount, OrderQueue orderQueue) {
        this.workers = new ArrayList<>();
        this.workerPool = Executors.newFixedThreadPool(workerCount);

        for (int i = 0; i < workerCount; i++) {
            workers.add(new KitchenWorker(i + 1, orderQueue));
        }
    }

    public void startWorkers() {
        for (KitchenWorker worker : workers) {
            workerPool.submit(worker);
        }
    }

    public void shutdown() {
        workerPool.shutdownNow();
    }

    public List<KitchenWorker> getWorkers() {
        return workers;
    }
}

// Kitchen Worker implementing Runnable
class KitchenWorker implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(KitchenWorker.class.getName());
    private final int workerId;
    private final OrderQueue orderQueue;
    private volatile String currentTask = "Idle";
    private volatile boolean busy = false;
    private Order currentOrder;

    public KitchenWorker(int workerId, OrderQueue orderQueue) {
        this.workerId = workerId;
        this.orderQueue = orderQueue;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    currentOrder = null;
                    busy = false;
                    currentTask = "Waiting for orders";

                    Order order = orderQueue.takeOrder();
                    if (order != null) {
                        currentOrder = order;
                        processOrder(order);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Worker " + workerId + " encountered an error: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    private void processOrder(Order order) {
        busy = true;
        try {
            currentTask = "Starting preparation of " + order.getItemName() +
                          " (Order #" + order.getId() + ", Complexity: " + order.getComplexity() + ")"; // Use getComplexity

            // Process based on item type
            switch (order.getItemName()) {
                case "Chicken Burger":
                    simulateBurgerPreparation(order);
                    break;
                case "Pizza":
                    simulatePizzaPreparation(order);
                    break;
                case "Pasta":            // Changed from Pasta Dish
                    simulatePastaPreparation(order);
                    break;
                case "Salad Mix":        // Changed from Fresh Salad
                    simulateSaladPreparation(order);
                    break;
                default:
                    currentTask = "Unknown order type";
                    return;
            }

            // Complete the order and ensure it's visible in completed list
            orderQueue.completeOrder(order);
            currentTask = "Completed Order #" + order.getId() + " - " + order.getItemName() +
                          " (Complexity: " + order.getComplexity() + ")"; // Use getComplexity
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            busy = false;
            currentOrder = null;
        }
    }

    private void simulateBurgerPreparation(Order order) throws InterruptedException {
        currentTask = "Grilling chicken for Order #" + order.getId();
        Thread.sleep(2000);
        currentTask = "Assembling burger for Order #" + order.getId();
        Thread.sleep(2000);
    }

    private void simulatePizzaPreparation(Order order) throws InterruptedException {
        currentTask = "Rolling pizza dough for Order #" + order.getId();
        Thread.sleep(2000);
        currentTask = "Adding toppings for Order #" + order.getId();
        Thread.sleep(1500);
        currentTask = "Baking pizza for Order #" + order.getId();
        Thread.sleep(1500);
    }

    private void simulatePastaPreparation(Order order) throws InterruptedException {
        currentTask = "Boiling pasta for Order #" + order.getId();
        Thread.sleep(1500);
        currentTask = "Preparing sauce for Order #" + order.getId();
        Thread.sleep(1500);
    }

    private void simulateSaladPreparation(Order order) throws InterruptedException {
        currentTask = "Washing vegetables for Order #" + order.getId();
        Thread.sleep(1000);
        currentTask = "Assembling salad for Order #" + order.getId();
        Thread.sleep(1000);
    }

    // Getters
    public int getWorkerId() { return workerId; }
    public String getCurrentTask() { return currentTask; }
    public boolean isBusy() { return busy; }
    public Order getCurrentOrder() { return currentOrder; }
}

// Inventory Management with Synchronization
class InventoryManager {
    private final Map<String, Integer> inventory = new ConcurrentHashMap<>();
    private final ReadWriteLock inventoryLock = new ReentrantReadWriteLock();
    private final Lock readLock = inventoryLock.readLock();
    private final Lock writeLock = inventoryLock.writeLock();

    public InventoryManager() {
        // Initialize with static values
        inventory.clear();
        inventory.put("Burger Buns", 20);
        inventory.put("Chicken Breast", 20);
        inventory.put("Pizza", 20);      // Changed from Pizza Dough
        inventory.put("Pasta", 20);
        inventory.put("Salad Mix", 20);
    }

    public boolean consumeIngredient(String ingredient, int quantity) {
        writeLock.lock();
        try {
            int current = inventory.getOrDefault(ingredient, 0);
            if (current >= quantity) {
                inventory.put(ingredient, current - quantity);
                return true;
            }
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    public Map<String, Integer> getInventorySnapshot() {
        readLock.lock();
        try {
            return new HashMap<>(inventory);
        } finally {
            readLock.unlock();
        }
    }

    // Remove or modify restockAll to use fixed amounts
    public void restockAll() {
        writeLock.lock();
        try {
            inventory.put("Burger Buns", 20);
            inventory.put("Chicken Breast", 20);
            inventory.put("Pizza", 20);   // Changed from Pizza Dough
            inventory.put("Pasta", 20);
            inventory.put("Salad Mix", 20);
        } finally {
            writeLock.unlock();
        }
    }
}

// Metrics Collection with Concurrent Processing
class MetricsCollector {
    private final AtomicInteger totalOrders = new AtomicInteger(0);
    private final AtomicInteger completedOrders = new AtomicInteger(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final ConcurrentLinkedQueue<Long> recentProcessingTimes = new ConcurrentLinkedQueue<>();

    private final long startTime = System.currentTimeMillis();

    public double getThroughput() {
        double runningTimeMinutes = (long) ((System.currentTimeMillis() - startTime) / 1000.0 / 60.0);
        return runningTimeMinutes > 0 ? completedOrders.get() / runningTimeMinutes : 0;
    }

    public double getAverageProcessingTime() {
        int completed = completedOrders.get();
        if (completed == 0) return 0;
        return totalProcessingTime.get() / (double) completed;
    }

    public void recordOrderCompletion(Order order) {
        if (order != null && order.getCompletionTime() > 0) {
            completedOrders.incrementAndGet();
            long processingTime = order.getCompletionTime() - order.getOrderTime();
            totalProcessingTime.addAndGet(processingTime);
            recentProcessingTimes.offer(processingTime);

            // Keep only last 50 processing times
            while (recentProcessingTimes.size() > 50) {
                recentProcessingTimes.poll();
            }
        }
    }

    public void recordNewOrder() {
        totalOrders.incrementAndGet();
    }

    // Getters
    public int getTotalOrders() { return totalOrders.get(); }
    public int getCompletedOrders() { return completedOrders.get(); }
    public int getPendingOrders() { return totalOrders.get() - completedOrders.get(); }
}

// Data Classes
class Order {
    private int id;
    private final String itemName;
    private long orderTime;
    private long completionTime;
    private final int complexity; // Added field

    public Order(String itemName, int complexity) {
        this.itemName = itemName;
        this.complexity = complexity; // Store complexity
    }

    public int getComplexity() { // Added getter
        return complexity;
    }

    // Remove unused getters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getItemName() { return itemName; }
    public long getOrderTime() { return orderTime; }
    public void setOrderTime(long orderTime) { this.orderTime = orderTime; }
    public long getCompletionTime() { return completionTime; }
    public void setCompletionTime(long completionTime) { this.completionTime = completionTime; }

    @Override
    public String toString() {
        return String.format("Order #%d: %s", id, itemName);
    }
}

// Background Tasks (Runnable implementations)
class MetricsUpdateTask implements Runnable {
    private final MetricsCollector metricsCollector;
    private final OrderQueue orderQueue;
    private final Set<Integer> processedOrders = new HashSet<>();

    public MetricsUpdateTask(MetricsCollector metricsCollector, OrderQueue orderQueue) {
        this.metricsCollector = metricsCollector;
        this.orderQueue = orderQueue;
    }

    @Override
    public void run() {
        // Update metrics with completed orders
        List<Order> completed = orderQueue.getCompletedOrders();
        for (Order order : completed) {
            if (!processedOrders.contains(order.getId())) {
                metricsCollector.recordOrderCompletion(order);
                processedOrders.add(order.getId());
            }
        }
    }
}

// GUI Components
class DashboardUI extends JPanel {
    private static final Logger LOGGER = Logger.getLogger(DashboardUI.class.getName());
    private final CafeteriaSystem cafeteriaSystem;

    // UI Components
    private JLabel systemStatusLabel;
    private JList<String> workerStatusList;
    private DefaultListModel<String> workerListModel;
    private JList<Order> pendingOrdersList;
    private DefaultListModel<Order> pendingOrdersModel;
    private JList<Order> completedOrdersList;
    private DefaultListModel<Order> completedOrdersModel;
    private JTextArea inventoryArea;
    private JLabel metricsLabel;
    private JComboBox<String> itemComboBox;  // Add this field
    private JButton addOrderButton;
    private JButton restockButton;
    private JButton performanceTestButton;

    public DashboardUI(CafeteriaSystem cafeteriaSystem) {
        this.cafeteriaSystem = cafeteriaSystem;
        initializeComponents();
        layoutComponents();
        setupEventHandlers();

        // Update UI every second
        // Use fully qualified name for Timer
        Timer uiUpdateTimer = new Timer(1000, _ -> updateUI());
        uiUpdateTimer.start();
    }

    private void initializeComponents() {
        systemStatusLabel = new JLabel("System Status: Starting...");

        workerListModel = new DefaultListModel<>();
        workerStatusList = new JList<>(workerListModel);

        pendingOrdersModel = new DefaultListModel<>();
        pendingOrdersList = new JList<>(pendingOrdersModel);

        completedOrdersModel = new DefaultListModel<>();
        completedOrdersList = new JList<>(completedOrdersModel);

        inventoryArea = new JTextArea(10, 20);
        inventoryArea.setEditable(false);

        metricsLabel = new JLabel("<html>Metrics:<br/>Loading...</html>");

        // Replace the addOrderButton initialization with these components
        itemComboBox = new JComboBox<>();
        updateAvailableItems(); // Initial population of combo box
        addOrderButton = new JButton("Add Order");

        restockButton = new JButton("Restock");
        performanceTestButton = new JButton("Run Performance Test");
    }

    private void layoutComponents() {
        setLayout(new BorderLayout());

        // Top panel - System status
        JPanel topPanel = new JPanel(new FlowLayout());
        topPanel.add(systemStatusLabel);
        topPanel.add(new JLabel("Select Item: "));  // Add label for dropdown
        topPanel.add(itemComboBox);
        topPanel.add(addOrderButton);
        topPanel.add(restockButton);
        topPanel.add(performanceTestButton);
        add(topPanel, BorderLayout.NORTH);

        // Main content
        JPanel mainPanel = new JPanel(new GridLayout(2, 3, 5, 5));

        // Workers panel
        JPanel workersPanel = new JPanel(new BorderLayout());
        workersPanel.setBorder(new TitledBorder("Kitchen Workers"));
        workersPanel.add(new JScrollPane(workerStatusList), BorderLayout.CENTER);
        mainPanel.add(workersPanel);

        // Pending orders panel
        JPanel pendingPanel = new JPanel(new BorderLayout());
        pendingPanel.setBorder(new TitledBorder("Pending Orders"));
        pendingPanel.add(new JScrollPane(pendingOrdersList), BorderLayout.CENTER);
        mainPanel.add(pendingPanel);

        // Completed orders panel
        JPanel completedPanel = new JPanel(new BorderLayout());
        completedPanel.setBorder(new TitledBorder("Completed Orders"));
        completedPanel.add(new JScrollPane(completedOrdersList), BorderLayout.CENTER);
        mainPanel.add(completedPanel);

        // Inventory panel
        JPanel inventoryPanel = new JPanel(new BorderLayout());
        inventoryPanel.setBorder(new TitledBorder("Inventory Status"));
        inventoryPanel.add(new JScrollPane(inventoryArea), BorderLayout.CENTER);
        mainPanel.add(inventoryPanel);

        // Metrics panel
        JPanel metricsPanel = new JPanel(new BorderLayout());
        metricsPanel.setBorder(new TitledBorder("Performance Metrics"));
        metricsPanel.add(metricsLabel, BorderLayout.CENTER);
        mainPanel.add(metricsPanel);

        // Performance test results
        JPanel performancePanel = new JPanel(new BorderLayout());
        performancePanel.setBorder(new TitledBorder("Performance Test Results"));
        JTextArea performanceArea = new JTextArea();
        performanceArea.setEditable(false);
        performancePanel.add(new JScrollPane(performanceArea), BorderLayout.CENTER);
        mainPanel.add(performancePanel);

        add(mainPanel, BorderLayout.CENTER);
    }

    private void setupEventHandlers() {
        addOrderButton.addActionListener(_ -> addRandomOrder());
        restockButton.addActionListener(_ -> performEmergencyRestock());
        performanceTestButton.addActionListener(_ -> runPerformanceTest());
    }

    private void updateAvailableItems() {
        itemComboBox.removeAllItems();
        Map<String, Integer> inventory = cafeteriaSystem.getInventoryManager().getInventorySnapshot();

        // Only add items that have stock available
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            if (entry.getValue() > 0) {
                switch (entry.getKey()) {
                    case "Burger Buns":
                        if (inventory.getOrDefault("Chicken Breast", 0) > 0) {
                            itemComboBox.addItem("Chicken Burger");
                        }
                        break;
                    case "Pizza":         // Changed from Pizza Dough
                        itemComboBox.addItem("Pizza");
                        break;
                    case "Pasta":
                        itemComboBox.addItem("Pasta");  // Changed from Pasta Dish
                        break;
                    case "Salad Mix":
                        itemComboBox.addItem("Salad Mix");  // Changed from Fresh Salad
                        break;
                }
            }
        }
    }

    private void addRandomOrder() {
        String selectedItem = (String) itemComboBox.getSelectedItem();
        if (selectedItem == null) {
            JOptionPane.showMessageDialog(this, "No items available to order!");
            return;
        }

        // Remove customer selection since we don't need it anymore
        int complexity = 0; // Keep complexity
        boolean orderPlaced = false;

        // First check and consume ingredients
        switch (selectedItem) {
            case "Chicken Burger":
                if (cafeteriaSystem.processOrderIngredients("Chicken Burger")) {
                    complexity = 3;
                    orderPlaced = true;
                } else {
                    JOptionPane.showMessageDialog(this, "Not enough ingredients for Chicken Burger!");
                }
                break;
            case "Pizza":
                if (cafeteriaSystem.processOrderIngredients("Pizza")) {
                    complexity = 4;
                    orderPlaced = true;
                } else {
                    JOptionPane.showMessageDialog(this, "Not enough Pizza!");  // Changed message
                }
                break;
            case "Pasta":                // Changed from Pasta Dish
                if (cafeteriaSystem.processOrderIngredients("Pasta")) {
                    complexity = 2;
                    orderPlaced = true;
                } else {
                    JOptionPane.showMessageDialog(this, "Not enough Pasta!");
                }
                break;
            case "Salad Mix":            // Changed from Fresh Salad
                if (cafeteriaSystem.processOrderIngredients("Salad Mix")) {
                    complexity = 1;
                    orderPlaced = true;
                } else {
                    JOptionPane.showMessageDialog(this, "Not enough Salad Mix!");
                }
                break;
            default:
                return;
        }

        if (orderPlaced) {
            Order order = new Order(selectedItem, complexity); // Pass complexity
            cafeteriaSystem.getOrderQueue().addOrder(order);
            cafeteriaSystem.getMetricsCollector().recordNewOrder(); // This increments total orders

            // Force immediate UI update
            SwingUtilities.invokeLater(() -> {
                // Update metrics immediately for new order
                MetricsCollector metrics = cafeteriaSystem.getMetricsCollector();
                String metricsText = String.format(
                        "<html>Total Orders: %d<br/>" +
                        "Completed: %d<br/>" +
                        "Pending: %d<br/>" +
                        "Avg Processing Time: %.1fms<br/>" +
                        "Throughput: %.2f orders/min</html>",
                        metrics.getTotalOrders(),
                        metrics.getCompletedOrders(),
                        metrics.getPendingOrders(),
                        metrics.getAverageProcessingTime(),
                        metrics.getThroughput()
                );
                metricsLabel.setText(metricsText);

                // Update pending orders list
                List<Order> pendingOrders = cafeteriaSystem.getOrderQueue().getPendingOrders();
                pendingOrdersModel.clear();
                for (Order pendingOrder : pendingOrders) {
                    pendingOrdersModel.addElement(pendingOrder);
                }

                updateUI();
                updateAvailableItems();
            });
        }
    }

    private void performEmergencyRestock() {
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                cafeteriaSystem.getInventoryManager().restockAll();
                return null;
            }

            @Override
            protected void done() {
                // Update the dropdown menu after restock
                updateAvailableItems();
                JOptionPane.showMessageDialog(DashboardUI.this, "Restock completed!");
            }
        };
        worker.execute();
    }

    private void runPerformanceTest() {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                return performConcurrencyTest();
            }

            @Override
            protected void done() {
                try {
                    String results = get();
                    JOptionPane.showMessageDialog(DashboardUI.this,
                            "Performance Test Results:\n" + results);
                } catch (Exception ex) {
                    LOGGER.severe("Performance test failed: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    private String performConcurrencyTest() {
        StringBuilder results = new StringBuilder();

        // Test 1: Thread creation and joining
        long startTime = System.currentTimeMillis();
        List<Thread> testThreads = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            testThreads.add(t);
            t.start();
        }

        // Join all threads
        for (Thread t : testThreads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long threadTestTime = System.currentTimeMillis() - startTime;
        results.append("Thread Creation/Joining Test: ").append(threadTestTime).append("ms\n");

        // Test 2: Parallel processing with reduction
        startTime = System.currentTimeMillis();
        int sum = IntStream.range(1, 1000000)
                .parallel()
                .reduce(0, Integer::sum);
        long parallelReductionTime = System.currentTimeMillis() - startTime;
        results.append("Parallel Reduction Test: ").append(parallelReductionTime).append("ms (Sum: ").append(sum).append(")\n");

        // Test 3: Lock contention test
        startTime = System.currentTimeMillis();
        ReentrantLock testLock = new ReentrantLock();
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    testLock.lock();
                    try {
                        // Simulate critical section
                        Thread.yield();
                    } finally {
                        testLock.unlock();
                    }
                }
                latch.countDown();
            }).start();
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long lockTestTime = System.currentTimeMillis() - startTime;
        results.append("Lock Contention Test: ").append(lockTestTime).append("ms\n");

        return results.toString();
    }

    @Override  // Modify the updateUI method
    public void updateUI() {
        super.updateUI();
        if (systemStatusLabel != null) {
            // Update system status
            systemStatusLabel.setText("System Status: " +
                    (cafeteriaSystem.isSystemRunning() ? "Running" : "Stopped"));

            // Update worker status with real-time info
            workerListModel.clear();
            for (KitchenWorker worker : cafeteriaSystem.getKitchenStaff().getWorkers()) {
                StringBuilder status = new StringBuilder();
                status.append(String.format("Worker %d: %s",
                    worker.getWorkerId(),
                    worker.isBusy() ? "🔴 BUSY" : "🟢 AVAILABLE"));

                Order currentOrder = worker.getCurrentOrder();
                if (currentOrder != null) {
                    status.append(String.format(" | Order #%d: %s",
                        currentOrder.getId(),
                        currentOrder.getItemName()));
                }

                status.append(String.format(" | Status: %s", worker.getCurrentTask()));

                workerListModel.addElement(status.toString());
            }

            // Update pending orders in real-time
            pendingOrdersModel.clear();
            List<Order> pendingOrders = cafeteriaSystem.getOrderQueue().getPendingOrders();
            for (Order order : pendingOrders) {
                pendingOrdersModel.addElement(order);
            }

            // Update completed orders with synchronized access
            List<Order> completed = cafeteriaSystem.getOrderQueue().getCompletedOrders();
            completedOrdersModel.clear();
            for (Order order : completed) {
                completedOrdersModel.addElement(order);
            }

            // Update inventory status in real-time
            Map<String, Integer> inventory = cafeteriaSystem.getInventoryManager().getInventorySnapshot();
            StringBuilder inventoryText = new StringBuilder();
            for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
                inventoryText.append(entry.getKey())
                           .append(": ")
                           .append(entry.getValue())
                           .append("\n");
            }
            inventoryArea.setText(inventoryText.toString());

            // Update metrics
            MetricsCollector metrics = cafeteriaSystem.getMetricsCollector();
            String metricsText = String.format(
                    "<html>Total Orders: %d<br/>" +
                    "Completed: %d<br/>" +
                    "Pending: %d</html>", // Removed Avg Processing Time and Throughput
                    metrics.getTotalOrders(),
                    metrics.getCompletedOrders(),
                    metrics.getTotalOrders() - metrics.getCompletedOrders() // Pending is total minus completed
            );
            metricsLabel.setText(metricsText);
        }
    }
}
