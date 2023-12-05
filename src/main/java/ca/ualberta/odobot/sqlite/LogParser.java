package ca.ualberta.odobot.sqlite;

import ca.ualberta.odobot.sqlite.impl.DbLogEntry;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {

    private static final Logger log = LoggerFactory.getLogger(LogParser.class);
    public static final DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd kk:mm:ss.SSS zzz");
    private static final Pattern statementPattern = Pattern.compile("(?<=\")[\\S\\s]*(?=\")");
    Consumer<DbLogEntry> logEntryConsumer;

    public int parseCount = 0;

    public LogParser(Consumer<DbLogEntry> onLogEntry){
        logEntryConsumer = onLogEntry;
    }

    public void parseDatabaseLogFile(String path){

        try{
            Reader in = new FileReader(path);
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.parse(in);

            for(CSVRecord record: records){

                String message = record.get(13);

                if(!message.startsWith("AUDIT:")){
                    //If the message for this log entry isn't an audit log, we don't care about it
                    continue;
                }

                //Need these two to make the primary key
                String sessionId = record.get(5);
                String sessionLineNumber = record.get(6);

                String key = sessionId + "-" + sessionLineNumber;
                ZonedDateTime timestamp = ZonedDateTime.parse(record.get(0), timestampFormat);

                //Now let's parse the message line according to:
                //https://github.com/pgaudit/pgaudit/blob/master/README.md

                //Strip 'AUDIT:'
                message = message.substring(6);

                String [] parts = message.split(",");
                String type = parts[3];
                String command = parts[4];
                String objectType = parts[5];
                String objectName = parts[6];
                String parameters = parts[parts.length - 2];

                log.debug("Extracting statement from: {}", message);
                Matcher matcher = statementPattern.matcher(message);
                matcher.find();
                String statement = matcher.group(0);

                DbLogEntry logEntry = new DbLogEntry(
                        key,
                        timestamp,
                        timestamp.toInstant().toEpochMilli(),
                        type,
                        command,
                        objectType,
                        objectName,
                        statement,
                        parameters
                );

                logEntryConsumer.accept(logEntry);
                parseCount++;
            }

        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


    }



}
