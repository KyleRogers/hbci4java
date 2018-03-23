package net.cyphoria.banking.prototyp;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.kapott.hbci.GV.HBCIJob;
import org.kapott.hbci.GV_Result.GVRKUms;
import org.kapott.hbci.callback.AbstractHBCICallback;
import org.kapott.hbci.manager.BankInfo;
import org.kapott.hbci.manager.HBCIHandler;
import org.kapott.hbci.manager.HBCIUtils;
import org.kapott.hbci.manager.HBCIVersion;
import org.kapott.hbci.passport.AbstractHBCIPassport;
import org.kapott.hbci.passport.HBCIPassport;
import org.kapott.hbci.status.HBCIExecStatus;
import org.kapott.hbci.structures.Konto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * @author Stefan Pennndorf
 */
@ShellComponent
public class KontoAbfrage {

    private final static HBCIVersion VERSION = HBCIVersion.HBCI_300;



    @Autowired
    @Lazy
    private Terminal terminal;


    @ShellMethod("Listet alle Konten")
    public void list() throws Exception {

        final LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();
        final String bankleitzahl = lineReader.readLine("Bankleitzahl: ");

        final String zugangsnummer = lineReader.readLine("Zugangsnummer: ");

        terminal.flush();
        final String pin = lineReader.readLine("password>",'*');

        Properties props = new Properties();
        props.put("log.loglevel.default", "10");
        props.put("log.filter", "0");
        HBCIUtils.init(props,new KontoAbfrage.MyHBCICallback(bankleitzahl, zugangsnummer, pin));

        // Wir setzen die Kernel-Parameter zur Laufzeit. Wir koennten sie alternativ
        // auch oben in "props" setzen.
        final File passportFile = new File("testpassport.dat");

        HBCIUtils.setParam("client.passport.default","PinTan"); // Legt als Verfahren PIN/TAN fest.
        HBCIUtils.setParam("client.passport.PinTan.filename",passportFile.getAbsolutePath());
        HBCIUtils.setParam("client.passport.PinTan.init","1");

        // Erzeugen des Passport-Objektes.
        HBCIPassport passport = AbstractHBCIPassport.getInstance();

        // Konfigurieren des Passport-Objektes.
        // Das kann alternativ auch alles ueber den Callback unten geschehen

        // Das Land.
        passport.setCountry("DE");

        BankInfo info = HBCIUtils.getBankInfo(bankleitzahl);
        passport.setHost(info.getPinTanAddress());

        passport.setPort(443);

        // Art der Nachrichten-Codierung. Bei Chipkarte/Schluesseldatei wird
        // "None" verwendet. Bei PIN/TAN kommt "Base64" zum Einsatz.
        passport.setFilterType("Base64");

        // Das Handle ist die eigentliche HBCI-Verbindung zum Server
        HBCIHandler handle = null;

        try {
            // Verbindung zum Server aufbauen
            handle = new HBCIHandler(VERSION.getId(), passport);

            Konto[] konten = passport.getAccounts();

            if (konten.length == 0) {
                terminal.writer().println("Keine Konten gefunden!");

                return;
            } else {
                terminal.writer().format("Es wurden %d Konten gefunden.\n", konten.length);

                for (Konto k : konten) {
                    terminal.writer().format("Konto: %s - %s (%s)\n", k.iban, k.name, k.acctype);
                }
            }

            HBCIJob umsatzJob = handle.newJob("KUmsAll");
            umsatzJob.setParam("my",konten[0]); // festlegen, welches Konto abgefragt werden soll.
            umsatzJob.addToQueue(); // Zur Liste der auszufuehrenden Auftraege hinzufuegen

            // Alle Auftraege aus der Liste ausfuehren.
            HBCIExecStatus status = handle.execute();
            GVRKUms result = (GVRKUms) umsatzJob.getJobResult();

            // Alle Umsatzbuchungen ausgeben
            List<GVRKUms.UmsLine> buchungen = result.getFlatData();

            terminal.writer().format("Es wurden %d Buchungen gefunden.\n", buchungen.size());

        } finally {
            if (handle != null) {
                handle.close();
            }
            passport.close();
        }

    }

    /**
     * Ueber diesen Callback kommuniziert HBCI4Java mit dem Benutzer und fragt die benoetigten
     * Informationen wie Benutzerkennung, PIN usw. ab.
     */
    private static class MyHBCICallback extends AbstractHBCICallback
    {


        private final String bankleitzahl;
        private final String zugangsnummer;
        private final String pin;

        public MyHBCICallback(String bankleitzahl, String zugangsnummer, String pin) {
            this.bankleitzahl = bankleitzahl;
            this.zugangsnummer = zugangsnummer;
            this.pin = pin;
        }

        /**
         * @see org.kapott.hbci.callback.HBCICallback#log(java.lang.String, int, java.util.Date, java.lang.StackTraceElement)
         */
        @Override
        public void log(String msg, int level, Date date, StackTraceElement trace)
        {
            // Ausgabe von Log-Meldungen bei Bedarf
            System.out.println(msg);
        }

        /**
         * @see org.kapott.hbci.callback.HBCICallback#callback(org.kapott.hbci.passport.HBCIPassport, int, java.lang.String, int, java.lang.StringBuffer)
         */
        @Override
        public void callback(HBCIPassport passport, int reason, String msg, int datatype, StringBuffer retData)
        {
            // Diese Funktion ist wichtig. Ueber die fragt HBCI4Java die benoetigten Daten von uns ab.
            switch (reason)
            {
                // Mit dem Passwort verschluesselt HBCI4Java die Passport-Datei.
                // Wir nehmen hier der Einfachheit halber direkt die PIN. In der Praxis
                // sollte hier aber ein staerkeres Passwort genutzt werden.
                // Die Ergebnis-Daten muessen in dem StringBuffer "retData" platziert werden.
                case NEED_PASSPHRASE_LOAD:
                case NEED_PASSPHRASE_SAVE:
                    retData.replace(0,retData.length(),pin);
                    break;

                // PIN wird benoetigt
                case NEED_PT_PIN:
                    retData.replace(0,retData.length(),pin);
                    break;

                // BLZ wird benoetigt
                case NEED_BLZ:
                    retData.replace(0,retData.length(),bankleitzahl);
                    break;

                // Die Benutzerkennung
                case NEED_USERID:
                    retData.replace(0,retData.length(),zugangsnummer);
                    break;

                // Die Kundenkennung. Meist identisch mit der Benutzerkennung.
                // Bei manchen Banken kann man die auch leer lassen
                case NEED_CUSTOMERID:
                    retData.replace(0,retData.length(),zugangsnummer);
                    break;

                // Manche Fehlermeldungen werden hier ausgegeben
                case HAVE_ERROR:
                    System.err.println(msg);
                    break;

                default:
                    // Wir brauchen nicht alle der Callbacks
                    break;

            }
        }

        /**
         * @see org.kapott.hbci.callback.HBCICallback#status(org.kapott.hbci.passport.HBCIPassport, int, java.lang.Object[])
         */
        @Override
        public void status(HBCIPassport passport, int statusTag, Object[] o)
        {
            // So aehnlich wie log(String,int,Date,StackTraceElement) jedoch fuer Status-Meldungen.
        }

    }
}
