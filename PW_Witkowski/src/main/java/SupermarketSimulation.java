import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import java.awt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;


class Config {
    @JsonProperty("liczba_kas")
    public int liczbKas;
    @JsonProperty("max_klientow")
    public int maxKlientow;
    @JsonProperty("czas_obslugi_min")
    public int czasObslugiMin;
    @JsonProperty("czas_obslugi_max")
    public int czasObslugiMax;
    @JsonProperty("czas_przerwy_kasjera")
    public int czasPrzerwyKasjera;
    @JsonProperty("czas_przychodu_klienta")
    public int czasPrzychoduKlienta;
    @JsonProperty("szansa_na_przerwe")
    public double szansaNaPrzerwe;

    public Config copy() {
        Config copy = new Config();
        copy.liczbKas = this.liczbKas;
        copy.maxKlientow = this.maxKlientow;
        copy.czasObslugiMin = this.czasObslugiMin;
        copy.czasObslugiMax = this.czasObslugiMax;
        copy.czasPrzerwyKasjera = this.czasPrzerwyKasjera;
        copy.czasPrzychoduKlienta = this.czasPrzychoduKlienta;
        copy.szansaNaPrzerwe = this.szansaNaPrzerwe;
        return copy;
    }
}

class Klient {
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private final int id;
    private final int czasObslugi;
    private final Semaphore obsluzony; // semafor do sygnalizacji zakończenia obsługi


    public Klient(int czasObslugi) {
        this.id = nextId.getAndIncrement();
        this.czasObslugi = czasObslugi;
        this.obsluzony = new Semaphore(0); // poczatkowo zablokowany
    }

    public int getId() { return id; }
    public int getCzasObslugi() { return czasObslugi; }

    public void oznaczJakoObsluzony() { obsluzony.release(); } // Oznacza klienta jako obsłużonego - zwalnia semafor

    public void czekajNaObsluge() throws InterruptedException { obsluzony.acquire(); }

    @Override
    public String toString() {
        return "Klient-" + id;
    }
}

class Kasa {
    private final int id;
    private final ConcurrentLinkedQueue<Klient> kolejka; //  kolejka klientów
    private final AtomicBoolean otwarta;
    private final AtomicBoolean kasjerDostepny; // czy kasjer jest nie na przerwie
    private final AtomicBoolean kasjerChcePrzerwe;
    private final ReentrantLock kasaMutex; // Mutex do synchronizacji operacji na kasie
    private final GUI gui;

    public Kasa(int id, GUI gui) {
        this.id = id;
        this.kolejka = new ConcurrentLinkedQueue<>();
        this.otwarta = new AtomicBoolean(true);
        this.kasjerDostepny = new AtomicBoolean(true);
        this.kasjerChcePrzerwe = new AtomicBoolean(false);
        this.kasaMutex = new ReentrantLock();
        this.gui = gui;
    }

    public int getId() { return id; }
    public ConcurrentLinkedQueue<Klient> getKolejka() { return kolejka; }
    public AtomicBoolean isKasjerDostepny() { return kasjerDostepny; }
    public AtomicBoolean isKasjerChcePrzerwe() { return kasjerChcePrzerwe; }
    public ReentrantLock getKasaMutex() { return kasaMutex; }

    public boolean dodajKlienta(Klient klient) {
        kasaMutex.lock(); // blokada
        try {
            if (czyPrzyjmujeKlientow()) {
                kolejka.offer(klient);
                gui.updateKasaDisplay(id, kolejka.size(), czyPrzyjmujeKlientow(), kasjerDostepny.get());
                System.out.println(klient + " dołączył do kolejki kasy " + id);
                return true;
            }
            return false;
        } finally {
            kasaMutex.unlock();
        }
    }

    public int rozmiarKolejki() {
        kasaMutex.lock();
        try {
            return kolejka.size();
        } finally {
            kasaMutex.unlock();
        }
    }

    public boolean czyPrzyjmujeKlientow() { //sprawdza czy kasa przyjmuje aktualnie klientow
        return otwarta.get() && kasjerDostepny.get() && !kasjerChcePrzerwe.get();
    }
    public void sygnalizujChęćPrzerwy() { // kasjer chce isc na przerwe - obsluguje reszte kolejki i idzie
        kasjerChcePrzerwe.set(true);
        gui.updateKasaDisplay(id, kolejka.size(), czyPrzyjmujeKlientow(), kasjerDostepny.get());
        System.out.println("Kasa " + id + " nie przyjmuje nowych klientów - kasjer chce przerwę");
    }

    public void anulujChęćPrzerwyBezAktualizacjiGUI() {
        kasjerChcePrzerwe.set(false);
    }
}

class Kasjer {
    private final Kasa kasa;
    private final Config config;
    private final Random random;
    private final GUI gui;
    private final AtomicBoolean running;
    private final ScheduledExecutorService scheduler; // scheduler do zarządzania zadaniami
    private ScheduledFuture<?> mainTask;
    private final AtomicBoolean currentlyServing = new AtomicBoolean(false);
    private final AtomicBoolean onBreak = new AtomicBoolean(false);

    public Kasjer(Kasa kasa, Config config, GUI gui, AtomicBoolean running, ScheduledExecutorService scheduler) {
        this.kasa = kasa;
        this.config = config;
        this.random = new Random();
        this.gui = gui;
        this.running = running;
        this.scheduler = scheduler;
    }

    public void start() { // uruchomienie kasjera i sprawdzanie kolejki co 100ms
        mainTask = scheduler.scheduleAtFixedRate(this::sprawdzKolejke, 0, 100, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (mainTask != null) {
            mainTask.cancel(false);
        }
        currentlyServing.set(false);
        onBreak.set(false);
    }

    private void sprawdzKolejke() {
        try {
            if (!running.get() || !kasa.isKasjerDostepny().get() ||
                    currentlyServing.get() || onBreak.get()) {
                return;
            }

            obsluzKolejkeZPrzerwami();
        } catch (Exception e) {
            System.err.println("Błąd w kasjerze " + kasa.getId() + ": " + e.getMessage());
        }
    }

    private void obsluzKolejkeZPrzerwami() { //obsługa kolejki

        if (kasa.getKolejka().isEmpty() && kasa.isKasjerChcePrzerwe().get()) {  // jak kolejka pusta i kasjer chce przerwę - idzie na przerwę
            kasa.anulujChęćPrzerwyBezAktualizacjiGUI();
            wykonajPrzerwe();
            return;
        }
        if (kasa.getKolejka().isEmpty() || !running.get() || !kasa.isKasjerDostepny().get()) { // jeśli brak klientów lub kasjer niedostępny - nie robi nic
            return;
        }
        // losowo decyduje czy chce przerwę z szansą co klienta
        if (!kasa.isKasjerChcePrzerwe().get() &&
                !kasa.getKolejka().isEmpty() &&
                random.nextDouble() < config.szansaNaPrzerwe) {

            kasa.sygnalizujChęćPrzerwy();
            System.out.println("Kasjer kasy " + kasa.getId() +
                    " chce przerwę po obsłudze pozostałych " + kasa.rozmiarKolejki() + " klientów");
        }
        Klient klient = kasa.getKolejka().poll(); // obsluga klienta
        if (klient != null) {
            obsluzKlienta(klient);
        }
    }

    private void obsluzKlienta(Klient klient) {
        if (currentlyServing.compareAndSet(false, true)) {
            System.out.println("Kasjer kasy " + kasa.getId() + " obsługuje " + klient +
                    " (pozostało w kolejce: " + kasa.rozmiarKolejki() +
                    (kasa.isKasjerChcePrzerwe().get() ? ", nie przyjmuje nowych" : "") + ")");

            scheduler.schedule(() -> {
                try {
                    klient.oznaczJakoObsluzony();
                    System.out.println("Kasjer kasy " + kasa.getId() + " zakończył obsługę " + klient);

                    SwingUtilities.invokeLater(() -> {
                        gui.updateKasaDisplay(kasa.getId(), kasa.rozmiarKolejki(),
                                kasa.czyPrzyjmujeKlientow(), kasa.isKasjerDostepny().get());
                    });
                } finally {
                    currentlyServing.set(false);
                }
            }, klient.getCzasObslugi(), TimeUnit.MILLISECONDS);
        }
    }

    private void wykonajPrzerwe() { //wykonywanie przerwy
        if (onBreak.compareAndSet(false, true)) {
            System.out.println("Kasjer kasy " + kasa.getId() + " idzie na przerwę (kolejka opróżniona)");

            kasa.isKasjerDostepny().set(false); // kasjer niedostepny
            SwingUtilities.invokeLater(() -> {
                gui.updateKasaDisplay(kasa.getId(), kasa.rozmiarKolejki(),
                        kasa.czyPrzyjmujeKlientow(), false);
            });

            scheduler.schedule(() -> {
                try {
                    kasa.isKasjerDostepny().set(true);
                    System.out.println("Kasjer kasy " + kasa.getId() + " wrócił z przerwy");
                    SwingUtilities.invokeLater(() -> {
                        gui.updateKasaDisplay(kasa.getId(), kasa.rozmiarKolejki(),
                                kasa.czyPrzyjmujeKlientow(), true);
                    });
                } finally {
                    onBreak.set(false);
                }
            }, config.czasPrzerwyKasjera, TimeUnit.MILLISECONDS);
        }
    }
}

class GeneratorKlientow { //generowanie klientów
    private final List<Kasa> kasy;
    private final Config config;
    private final Semaphore miejscaWSklepie; // semafor ograniczający liczbę klientów w sklepie
    private final Random random;
    private final AtomicBoolean running;
    private final GUI gui;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> generatorTask;

    public GeneratorKlientow(List<Kasa> kasy, Config config, Semaphore miejscaWSklepie,
                             AtomicBoolean running, GUI gui, ScheduledExecutorService scheduler) {
        this.kasy = kasy;
        this.config = config;
        this.miejscaWSklepie = miejscaWSklepie;
        this.random = new Random();
        this.running = running;
        this.gui = gui;
        this.scheduler = scheduler;
    }

    public void start() { // generowanie klienta co czas okreslony w ustawieniach
        generatorTask = scheduler.scheduleAtFixedRate(this::sprobujDodacKlienta,
                0, config.czasPrzychoduKlienta, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (generatorTask != null) {
            generatorTask.cancel(false);
        }
    }

    private void sprobujDodacKlienta() {
        if (!running.get()) {
            return;
        }

        if (miejscaWSklepie.tryAcquire()) { //nowy klient wchodzi do sklepu
            boolean klientDodany = false;

            try {         // losuje czas obsługi dla nowego klienta

                int czasObslugi = config.czasObslugiMin +
                        random.nextInt(config.czasObslugiMax - config.czasObslugiMin + 1);
                Klient klient = new Klient(czasObslugi);
                Kasa wybranaKasa = wybierzNajkrotszaKolejke(); // szukanie najkrotszej kolejki
                if (wybranaKasa != null) {
                    if (wybranaKasa.dodajKlienta(klient)) {
                        klientDodany = true;
                        // uruchamia proces klienta (zakupy + oczekiwanie na obsługę)
                        new ProcesKlienta(klient, miejscaWSklepie, gui, config.maxKlientow, scheduler).start();
                        gui.updateCustomerCount(config.maxKlientow - miejscaWSklepie.availablePermits(), config.maxKlientow);
                        System.out.println(klient + " wszedł do sklepu (miejsca: " +
                                miejscaWSklepie.availablePermits() + "/" + config.maxKlientow + ")");
                    }
                }
                if (!klientDodany) {
                    miejscaWSklepie.release();
                }
            } catch (Exception e) {
                if (!klientDodany) {
                    miejscaWSklepie.release();
                }
                System.err.println("Błąd przy dodawaniu klienta: " + e.getMessage());
            }
        }
    }
    private Kasa wybierzNajkrotszaKolejke() { //szukanie najkrotszej kolejki
        Kasa najkrotsza = null;
        int minRozmiar = Integer.MAX_VALUE;

        for (Kasa kasa : kasy) {
            if (kasa.czyPrzyjmujeKlientow()) {
                int rozmiar = kasa.rozmiarKolejki();
                if (rozmiar < minRozmiar) {
                    minRozmiar = rozmiar;
                    najkrotsza = kasa;
                }
            }
        }

        return najkrotsza;
    }
}

class ProcesKlienta {
    private final Klient klient;
    private final Semaphore miejscaWSklepie; // semafor miejsc w sklepie
    private final Random random = new Random();
    private final GUI gui;
    private final int maxKlientow;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> shoppingTask;

    public ProcesKlienta(Klient klient, Semaphore miejscaWSklepie, GUI gui,
                         int maxKlientow, ScheduledExecutorService scheduler) {
        this.klient = klient;
        this.miejscaWSklepie = miejscaWSklepie;
        this.gui = gui;
        this.maxKlientow = maxKlientow;
        this.scheduler = scheduler;
    }

    public void start() { // proces klienta
        //losuje czas zakupów (1-3 sekundy) - klient po wejsciu do sklepu nie idzie odrazu do kasy
        int czasZakupow = 1000 + random.nextInt(2000);

        shoppingTask = scheduler.schedule(() -> {
            System.out.println(klient + " zakończył zakupy i czeka w kolejce na obsługę");
            //czeka na obsluzenie
            CompletableFuture.runAsync(() -> {
                try {
                    klient.czekajNaObsluge();
                    System.out.println(klient + " został obsłużony i opuszcza sklep");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    opuscSklep();
                }
            });
        }, czasZakupow, TimeUnit.MILLISECONDS);
    }

    private void opuscSklep() { //zwalnianie miejsca i aktualizacja gui
        miejscaWSklepie.release();
        SwingUtilities.invokeLater(() -> {
            gui.updateCustomerCount(maxKlientow - miejscaWSklepie.availablePermits(), maxKlientow);
        });
        System.out.println(klient + " opuścił sklep (wolne miejsca: " +
                miejscaWSklepie.availablePermits() + ")");
    }
}

class ConfigPanel extends JPanel { // panel konfiguracji
    private final Config config;

    // komponenty GUI dla poszczególnych parametrów
    private final JSpinner liczbKasSpinner;
    private final JSpinner maxKlientowSpinner;
    private final JSpinner czasObslugiMinSpinner;
    private final JSpinner czasObslugiMaxSpinner;
    private final JSpinner czasPrzerwyKasjeraSpinner;
    private final JSpinner czasPrzychoduKlientaSpinner;
    private final JSpinner szansaNaPrzeweSpinner;


    public ConfigPanel(Config config) {
        this.config = config;
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder("Konfiguracja"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // utworzenie wszystkich spinnerów z odpowiednimi ograniczeniami
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 1.0;
        add(new JLabel("Liczba kas:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.5;
        liczbKasSpinner = new JSpinner(new SpinnerNumberModel(config.liczbKas, 1, 10, 1));
        liczbKasSpinner.setPreferredSize(new Dimension(100, 25));
        add(liczbKasSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        gbc.weightx = 1.0;
        add(new JLabel("Max klientów w sklepie:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.5;
        maxKlientowSpinner = new JSpinner(new SpinnerNumberModel(config.maxKlientow, 5, 100, 5));
        maxKlientowSpinner.setPreferredSize(new Dimension(100, 25));
        add(maxKlientowSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        gbc.weightx = 1.0;
        add(new JLabel("Min czas obsługi (ms):"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.5;
        czasObslugiMinSpinner = new JSpinner(new SpinnerNumberModel(config.czasObslugiMin, 100, 10000, 100));
        czasObslugiMinSpinner.setPreferredSize(new Dimension(100, 25));
        add(czasObslugiMinSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        gbc.weightx = 1.0;
        add(new JLabel("Max czas obsługi (ms):"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.5;
        czasObslugiMaxSpinner = new JSpinner(new SpinnerNumberModel(config.czasObslugiMax, 100, 10000, 100));
        czasObslugiMaxSpinner.setPreferredSize(new Dimension(100, 25));
        add(czasObslugiMaxSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 4;
        gbc.weightx = 1.0;
        add(new JLabel("Czas przerwy kasjera (ms):"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.5;
        czasPrzerwyKasjeraSpinner = new JSpinner(new SpinnerNumberModel(config.czasPrzerwyKasjera, 1000, 30000, 1000));
        czasPrzerwyKasjeraSpinner.setPreferredSize(new Dimension(100, 25));
        add(czasPrzerwyKasjeraSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 5;
        gbc.weightx = 1.0;
        add(new JLabel("Czas przychodu klienta (ms):"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.5;
        czasPrzychoduKlientaSpinner = new JSpinner(new SpinnerNumberModel(config.czasPrzychoduKlienta, 100, 5000, 100));
        czasPrzychoduKlientaSpinner.setPreferredSize(new Dimension(100, 25));
        add(czasPrzychoduKlientaSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 6;
        gbc.weightx = 1.0;
        JLabel szansaLabel = new JLabel("Szansa na przerwę (0.0-1.0):");
        szansaLabel.setPreferredSize(new Dimension(200, 25));
        add(szansaLabel, gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.5;
        szansaNaPrzeweSpinner = new JSpinner(new SpinnerNumberModel(config.szansaNaPrzerwe, 0.0, 1.0, 0.05));
        szansaNaPrzeweSpinner.setPreferredSize(new Dimension(100, 25));
        add(szansaNaPrzeweSpinner, gbc);

        // przycisk resetowania do wartości domyślnych
        gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton resetButton = new JButton("Resetuj do domyślnych");
        resetButton.addActionListener(e -> resetToDefaults());
        buttonPanel.add(resetButton);

        add(buttonPanel, gbc);

        setPreferredSize(new Dimension(400, 350));
    }
    // aktualizacja konfiguracji
    public void updateConfig() {
        config.liczbKas = (Integer) liczbKasSpinner.getValue();
        config.maxKlientow = (Integer) maxKlientowSpinner.getValue();
        config.czasObslugiMin = (Integer) czasObslugiMinSpinner.getValue();
        config.czasObslugiMax = (Integer) czasObslugiMaxSpinner.getValue();
        config.czasPrzerwyKasjera = (Integer) czasPrzerwyKasjeraSpinner.getValue();
        config.czasPrzychoduKlienta = (Integer) czasPrzychoduKlientaSpinner.getValue();
        config.szansaNaPrzerwe = (Double) szansaNaPrzeweSpinner.getValue();
    }

    public void refreshFromConfig() {
        liczbKasSpinner.setValue(config.liczbKas);
        maxKlientowSpinner.setValue(config.maxKlientow);
        czasObslugiMinSpinner.setValue(config.czasObslugiMin);
        czasObslugiMaxSpinner.setValue(config.czasObslugiMax);
        czasPrzerwyKasjeraSpinner.setValue(config.czasPrzerwyKasjera);
        czasPrzychoduKlientaSpinner.setValue(config.czasPrzychoduKlienta);
        szansaNaPrzeweSpinner.setValue(config.szansaNaPrzerwe);
    }

    private void resetToDefaults() {
        liczbKasSpinner.setValue(4);
        maxKlientowSpinner.setValue(20);
        czasObslugiMinSpinner.setValue(1000);
        czasObslugiMaxSpinner.setValue(3000);
        czasPrzerwyKasjeraSpinner.setValue(5000);
        czasPrzychoduKlientaSpinner.setValue(800);
        szansaNaPrzeweSpinner.setValue(0.15);
    }

    public boolean isConfigValid() {
        int minTime = (Integer) czasObslugiMinSpinner.getValue();
        int maxTime = (Integer) czasObslugiMaxSpinner.getValue();
        return minTime < maxTime;
    }
}

class GUI extends JFrame {
    private List<JPanel> kasaPanels;
    private final JLabel statusLabel;
    private final JLabel customerCountLabel;
    private final JButton startButton;
    private final JButton stopButton;
    private final JButton configButton;

    private final Map<Integer, Long> lastUpdateTime = new ConcurrentHashMap<>();
    private static final long MIN_UPDATE_INTERVAL = 100;

    private ConfigPanel configPanel;
    private final Config config;
    private JPanel kasaContainer;

    public GUI(Config config) {
        this.config = config;
        setTitle("Symulacja Supermarketu");
        setSize(1000, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // yworzenie panelu kontrolnego z przyciskami i statusem
        JPanel controlPanel = new JPanel(new FlowLayout());
        startButton = new JButton("Start");
        stopButton = new JButton("Stop");
        configButton = new JButton("Konfiguracja");
        statusLabel = new JLabel("Gotowy do startu");
        customerCountLabel = new JLabel("Klienci w sklepie: 0/0");

        // formatowanie labela
        customerCountLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        customerCountLabel.setForeground(Color.BLUE);
        customerCountLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));

        controlPanel.add(startButton);
        controlPanel.add(stopButton);
        controlPanel.add(configButton);
        controlPanel.add(Box.createHorizontalStrut(20));
        controlPanel.add(statusLabel);
        controlPanel.add(Box.createHorizontalStrut(20));
        controlPanel.add(customerCountLabel);

        add(controlPanel, BorderLayout.NORTH);
        createKasaContainer();
        configPanel = new ConfigPanel(config);

        setVisible(true);
        configButton.addActionListener(e -> showConfigDialog());
    }

    private void createKasaContainer() {
        if (kasaContainer != null) {
            remove(kasaContainer);
        }
        kasaContainer = new JPanel(new GridLayout(0, 3, 10, 10));
        kasaContainer.setBorder(BorderFactory.createTitledBorder("Kasy"));
        kasaPanels = new ArrayList<>();
        for (int i = 0; i < config.liczbKas; i++) {
            JPanel kasaPanel = createKasaPanel(i);
            kasaPanels.add(kasaPanel);
            kasaContainer.add(kasaPanel);
        }
        add(kasaContainer, BorderLayout.CENTER);
        updateCustomerCount(0, config.maxKlientow);
        revalidate();
        repaint();
    }
    private JPanel createKasaPanel(int kasaId) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Kasa " + kasaId));
        panel.setBackground(Color.LIGHT_GRAY);

        JLabel statusLabel = new JLabel("Zamknięta", JLabel.CENTER);
        JLabel kolejkaLabel = new JLabel("Kolejka: 0", JLabel.CENTER);
        JLabel kasjerLabel = new JLabel("Kasjer: Niedostępny", JLabel.CENTER);
        statusLabel.setName("status");
        kolejkaLabel.setName("kolejka");
        kasjerLabel.setName("kasjer");
        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(kolejkaLabel, BorderLayout.CENTER);
        panel.add(kasjerLabel, BorderLayout.SOUTH);

        return panel;
    }
    private void showConfigDialog() {
        JDialog dialog = new JDialog(this, "Konfiguracja symulacji", true);
        dialog.setLayout(new BorderLayout());

        configPanel.refreshFromConfig();
        dialog.add(configPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Anuluj");

        okButton.addActionListener(e -> {
            if (configPanel.isConfigValid()) {
                configPanel.updateConfig();
                createKasaContainer();
                dialog.dispose();
                JOptionPane.showMessageDialog(this, "Konfiguracja została zaktualizowana!",
                        "Konfiguracja", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        cancelButton.addActionListener(e -> dialog.dispose());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    // aktualizacja wyświetlania
    public void updateKasaDisplay(int kasaId, int rozmiarKolejki, boolean przyjmujeKlientow, boolean kasjerDostepny) {
        long currentTime = System.currentTimeMillis();
        Long lastUpdate = lastUpdateTime.get(kasaId);
        if (lastUpdate == null || currentTime - lastUpdate > MIN_UPDATE_INTERVAL) {
            SwingUtilities.invokeLater(() -> {
                if (kasaId < kasaPanels.size()) {
                    JPanel panel = kasaPanels.get(kasaId);
                    JLabel statusLabel = findLabelByName(panel, "status");
                    JLabel kolejkaLabel = findLabelByName(panel, "kolejka");
                    JLabel kasjerLabel = findLabelByName(panel, "kasjer");
                    if (statusLabel != null) {
                        String status;
                        Color backgroundColor;
                        if (!kasjerDostepny) { // zamiany kolorów w zależnosci od statusu kasy
                            status = "Kasjer na przerwie";
                            backgroundColor = Color.RED;
                        } else if (przyjmujeKlientow) {
                            status = "Otwarta";
                            backgroundColor = Color.GREEN;
                        } else {
                            status = "Nie przyjmuje nowych";
                            backgroundColor = Color.YELLOW;
                        }
                        statusLabel.setText(status);
                        panel.setBackground(backgroundColor);
                    }

                    if (kolejkaLabel != null) {
                        kolejkaLabel.setText("Kolejka: " + rozmiarKolejki);
                    }

                    if (kasjerLabel != null) {
                        kasjerLabel.setText("Kasjer: " + (kasjerDostepny ? "Dostępny" : "Na przerwie"));
                    }
                }
            });
            lastUpdateTime.put(kasaId, currentTime);
        }
    }

    public void updateCustomerCount(int currentCustomers, int maxCustomers) { //licznik klientów w symulacji
        SwingUtilities.invokeLater(() -> {
            customerCountLabel.setText("Klienci w sklepie: " + currentCustomers + "/" + maxCustomers);
            double fillRatio = (double) currentCustomers / maxCustomers;
            if (fillRatio < 0.5) {
                customerCountLabel.setForeground(Color.GREEN); // Mało klientów
            } else if (fillRatio < 0.8) {
                customerCountLabel.setForeground(Color.ORANGE); // Średnio klientów
            } else {
                customerCountLabel.setForeground(Color.RED); // Dużo klientów
            }
        });
    }
    private JLabel findLabelByName(Container container, String name) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JLabel && name.equals(comp.getName())) {
                return (JLabel) comp;
            }
        }
        return null;
    }
    public JButton getStartButton() { return startButton; }
    public JButton getStopButton() { return stopButton; }

    public void setStatus(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }
}

public class SupermarketSimulation { // głowna klasa zarządzająa symluacją
    private Config config;
    private List<Kasa> kasy;
    private Semaphore miejscaWSklepie;
    private List<Kasjer> kasjerzy;
    private GeneratorKlientow generatorKlientow;
    private AtomicBoolean running;
    private GUI gui;
    private ScheduledExecutorService mainScheduler;


    public SupermarketSimulation() {
        running = new AtomicBoolean(false);
        kasjerzy = new ArrayList<>();

        mainScheduler = Executors.newScheduledThreadPool(
                Runtime.getRuntime().availableProcessors() * 2);
    }
    public void loadConfig(String filename) throws IOException { // wczytanie konfiguracji z json
        ObjectMapper mapper = new ObjectMapper();
        config = mapper.readValue(new FileReader(filename), Config.class);
    }

    public void createDefaultConfig() { // domyslnie jak nie da sie z pliku
        config = new Config();
        config.liczbKas = 4;
        config.maxKlientow = 40;
        config.czasObslugiMin = 2000;
        config.czasObslugiMax = 4000;
        config.czasPrzerwyKasjera = 7000;
        config.czasPrzychoduKlienta = 600;
        config.szansaNaPrzerwe = 0.15;
    }

    public void initializeSimulation() { //wlaczenie symulacji i gui
        gui = new GUI(config);

        gui.getStartButton().addActionListener(e -> {
            if (!running.get()) {
                prepareSimulation();
                startSimulation();
            }
        });

        gui.getStopButton().addActionListener(e -> stopSimulation());
    }

    private void prepareSimulation() {

        kasy = new ArrayList<>();      // tworzenie kas
        for (int i = 0; i < config.liczbKas; i++) {
            kasy.add(new Kasa(i, gui));
        }

        miejscaWSklepie = new Semaphore(config.maxKlientow);
        kasjerzy.clear();
        for (Kasa kasa : kasy) {
            kasjerzy.add(new Kasjer(kasa, config, gui, running, mainScheduler));
        }

        generatorKlientow = new GeneratorKlientow(kasy, config, miejscaWSklepie,
                running, gui, mainScheduler);
    }

    public void startSimulation() { // start
        if (running.get()) return;

        running.set(true);
        gui.setStatus("Symulacja uruchomiona");
        refreshGUI();
        for (Kasjer kasjer : kasjerzy) {
            kasjer.start();
        }

        generatorKlientow.start();

        System.out.println("Symulacja uruchomiona z " + config.liczbKas +
                " kasami i max " + config.maxKlientow + " klientami");
    }

    private void refreshGUI() {
        gui.updateCustomerCount(0, config.maxKlientow);

        for (int i = 0; i < config.liczbKas; i++) {
            gui.updateKasaDisplay(i, 0, true, true); // 0 klientów, otwarta, kasjer dostępny
        }
    }
    public void stopSimulation() { //stop
        if (!running.get()) return;

        running.set(false);
        gui.setStatus("Zatrzymywanie symulacji...");

        for (Kasjer kasjer : kasjerzy) {
            kasjer.stop();
        }

        if (generatorKlientow != null) {
            generatorKlientow.stop();
        }

        gui.setStatus("Symulacja zatrzymana");
        System.out.println("Symulacja zatrzymana");
        gui.updateCustomerCount(0, config.maxKlientow);
    }

    public void shutdown() { // zamkniecie i zwolnienie zasobów
        stopSimulation();
        mainScheduler.shutdown();
        try {

            if (!mainScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                mainScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            mainScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SupermarketSimulation simulation = new SupermarketSimulation();

            try {
                // ładowanie konfiguracji z pliku
                simulation.loadConfig("config.json");
                System.out.println("Załadowano konfigurację z pliku config.json");
            } catch (IOException e) {
                System.err.println("Błąd podczas ładowania konfiguracji: " + e.getMessage());
                System.out.println("Używam domyślnej konfiguracji...");
                simulation.createDefaultConfig();
            }
            simulation.initializeSimulation();
            Runtime.getRuntime().addShutdownHook(new Thread(simulation::shutdown));
        });
    }
}