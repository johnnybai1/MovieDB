import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * To compile, make sure you have ojbdc6.jar in the same directory and
 * run: "java -cp ojdbc6.jar Populate.java
 * To run: "java -cp .:ojdbc6.jar Populate <file1.dat file2.dat ...>
 * The only tables/files we need are: movies, movie_genres, movie_directors,
 * movie_actors, movie_countries, tags, user_taggedmovies,
 * user_ratedmovies
 */

public class Populate {

    private static SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy");
    private HashMap<String, Charset> encodings;

    private void run(String[] args) {
        Connection con = null;
        ArrayList<Integer> columnTypes = null;
        try {
            // Step 1. Connect to the database
            con = openConnection();
            System.out.println("connection opened");
            // Step 2. Get Database metadata
            DatabaseMetaData dbmd = con.getMetaData();
            // Step 3. For each of the files specified, import data to appropriate tables
            for (String filename : args) {
                // Table name is file name without .dat extension
                String table = filename.replace(".dat", "");
                if (table.contains("-")) {
                    table = table.substring(0, table.indexOf("-"));
                }
                // Get column types / check table existence
                columnTypes = getColumnTypes(dbmd, table);
                int numColumns = columnTypes.size();
                if (numColumns > 0) {
                    // Delete all entries
                    deleteTable(con, table);
                    // Generate appropriate insert statement
                    String insertStatement = generateInsertSQL(table, numColumns);
                    PreparedStatement ps = con.prepareStatement(insertStatement);
                    buildEncodingsMap();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(
                            new FileInputStream("data/" + filename), encodings.get(filename)))) {
                        String line = br.readLine(); // Ignore headers
                        while ((line = br.readLine()) != null) {
                            String[] values = processLine(filename, line, numColumns); // Get values to be inserted
                            for (int i = 0; i < values.length; i++) {
                                if (values[i].equals("\\N")) {
                                    ps.setNull(i + 1, columnTypes.get(i));
                                }
                                else {
                                    if (columnTypes.get(i).equals(Types.TIMESTAMP)) {
                                        ps.setString(i +1, values[i]);
                                    }
                                    else {
                                        ps.setObject(i + 1, values[i], columnTypes.get(i));
                                    }
                                }
                            }
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    catch (IOException e) {
                        System.out.println(filename + " not found: " + e.getMessage());
                    }
                }
                else {
                    System.out.println("WARNING: You wanted to import " + filename
                            + " but the table does not exist.\nThe file may not be needed" +
                            " or you may need to run @createdb.sql");
                }
            }
        }
        catch (SQLException e) {
            while (e != null) {
                System.out.println("Message: " + e.getMessage());
                System.out.println("SQLState: " + e.getSQLState());
                System.out.println("Vendor Error: " + e.getErrorCode());
                e = e.getNextException();
            }
        }
        catch (ClassNotFoundException e) {
            System.err.println("Cannot find the database driver");
        }
        finally {
            closeConnection(con);
        }
    }

    private String[] processLine(String fileName, String line, int numColumns) {
        String[] values = new String[numColumns];
        String[] split = line.split("\t");
        switch (fileName) {
            case "movies.dat": {
                // movie_id, title, year, rtAllCriticsRating, rtAllCriticsNumReviews
                // rtTopCriticsRating, rtTopCriticsNumReviews, rtAudienceRating, rtAudienceNumReviews
                values[0] = split[0];
                values[1] = split[1];
                values[2] = split[5];
                // AllCriticsRating:7, TopRating:12, AudienceRating:17
                // AllCriticsNumReviews:8, TopNum:13, AudienceNum:18
                for (int i = 7; i < 18; i+=5) {
                    // i references ratings, i+1 references number
                    if (split[i].equals("\\N")) {
                        split[i] = "0";
                    }
                    if (split[i+1].equals("\\N")) {
                        split[i+1] = "0";
                    }
                }
                values[3] = split[7];
                values[4] = split[8];
                values[5] = split[12];
                values[6] = split[13];
                values[7] = split[17];
                values[8] = split[18];
                return values;
            }
            case "movie_genres.dat": {
                // movie_id, genre
                return split;
            }
            case "movie_directors.dat": {
                // movie_id, director_name
                values[0] = split[0];
                if (split[2].isEmpty()) {
                    values[1] = "N/A";
                }
                else {
                    values[1] = split[2];
                }
                return values;
            }
            case "movie_actors.dat": {
                // movie_id, actor_name
                values[0] = split[0];
                if (split[2].isEmpty()) {
                    values[1] = "N/A";
                }
                else {
                    values[1] = split[2];
                }
                return values;
            }
            case "movie_countries.dat": {
                // movie_id, country
                return split;
            }
            case "tags.dat": {
                // tag_id, value
                return split;
            }
            case "user_taggedmovies.dat": {
                // user_id, movie_id, tag_id
                for (int i = 0; i < 3; i++) {
                    values[i] = split[i];
                }
                return values;
            }
            case "user_taggedmovies-timestamps.dat": {
                // user_id, movie_id, tag_id, date
                for (int i = 0; i < 3; i++) {
                    values[i] = split[i];
                }
                return values;
            }
            case "user_ratedmovies.dat": {
                // user_id, movie_id, rating, date
                for (int i = 0; i < 3; i++) {
                    values[i] = split[i];
                }
                String date = split[4] + "/" + split[3] + "/" + split[5];
                values[3] = date;
                return values;
            }
            case "user_ratedmovies-timestamps.dat": {
                // user_id, movie_id, rating, date
                for (int i = 0; i < 3; i++) {
                    values[i] = split[i];
                }
                Date date = new Date(Long.parseLong(split[3]));
                values[3] = sdf.format(date);
                return values;
            }
        }
        return null;
    }

    private String generateInsertSQL(String table, int numColumns) {
        String insert = "INSERT INTO " + table + " VALUES (?";
        for (int i = 1; i < numColumns; i++) {
            insert += ",?";
        }
        if (table.startsWith("user_rated")) {
            insert = insert.substring(0, insert.length() - 1); // Remove last ?
            insert += "TO_DATE(?, 'MM/DD/YYYY')";
        }
        return insert + ")";
    }

    private void deleteTable(Connection con, String table) throws SQLException {
        String delete = "DELETE FROM " + table;
        Statement stmt = con.createStatement();
        stmt.execute(delete);
    }

    private ArrayList<Integer> getColumnTypes(DatabaseMetaData dbmd, String tableName) throws SQLException{
        String catalog = null;
        String schemaPattern = null;
        String columnNamePattern = null;
        ArrayList<Integer> columnTypes = new ArrayList<>();
        ResultSet result = dbmd.getColumns(catalog, schemaPattern, tableName.toUpperCase(), columnNamePattern);
        while (result.next()) {
            columnTypes.add(result.getInt(5));
        }
        return columnTypes;
    }

    private void buildEncodingsMap() {
        encodings = new HashMap<>();
        encodings.put("movie_actors.dat", StandardCharsets.ISO_8859_1);
        encodings.put("movie_countries.dat", StandardCharsets.US_ASCII);
        encodings.put("movie_directors.dat", StandardCharsets.ISO_8859_1);
        encodings.put("movie_genres.dat", StandardCharsets.US_ASCII);
        encodings.put("movies.dat", StandardCharsets.ISO_8859_1);
        encodings.put("tags.dat", StandardCharsets.ISO_8859_1);
        encodings.put("user_ratedmovies-timestamps.dat", StandardCharsets.US_ASCII);
        encodings.put("user_ratedmovies.dat", StandardCharsets.US_ASCII);
        encodings.put("user_taggedmovies-timestamps.dat", StandardCharsets.US_ASCII);
        encodings.put("user_taggedmovies.dat", StandardCharsets.US_ASCII);
    }

    private Connection openConnection() throws SQLException, ClassNotFoundException {
        DriverManager.registerDriver(new oracle.jdbc.OracleDriver());
        String host, port, dbName, userName, password;
        try (BufferedReader br = new BufferedReader(new FileReader(new File("src/connection.txt")))) {
            host = br.readLine().split(":")[1].trim();
            port = br.readLine().split(":")[1].trim();
            dbName = br.readLine().split(":")[1].trim();
            userName = br.readLine().split(":")[1].trim();
            password = br.readLine().split(":")[1].trim();
        }
        catch (IOException e) {
            System.out.println("Connection.txt not found, using values defined in Populate.java");
            host = "Johnny";
            port = "1521";
            dbName = "orcl";
            userName = "scott";
            password = "tiger";
        }
        String dbURL = "jdbc:oracle:thin:@" + host + ":" + port + ":" + dbName;
        System.out.println("Connecting to: " + dbURL);
        return DriverManager.getConnection(dbURL, userName, password);
    }

    private void closeConnection(Connection con) {
        try {
            con.close();
        }
        catch (SQLException e) {
            System.err.println("Cannot close connection: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws SQLException, ClassNotFoundException, IOException {
        if (args.length == 0) {
            System.err.println("No files inputted!");
        }

        if (args.length == 1 && args[0].equals("*")) {
            // Import everything
            args = new String[]
            {
                "movies.dat",
                "movie_actors.dat",
                "movie_countries.dat",
                "movie_directors.dat",
                "movie_genres.dat",
                "user_ratedmovies.dat",
                "user_taggedmovies.dat"
            };
        }
        else {
            boolean importedMovies = false;
            boolean importedTags = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("movies.dat")) {
                    // Parse movies.dat first (lots of dependencies)
                    args[i] = args[0];
                    args[0] = "movies.dat";
                    importedMovies = true;
                }
                if (args[i].equals("tags.dat")) {
                    // tags.dat also has dependencies
                    args[i] = args[1];
                    args[1] = "tags.dat";
                    importedTags = true;
                }
            }

            if (!importedMovies) {
                System.out.println("Warning: Things may not work properly if movies.dat is not imported!");
            }

            if (!importedTags) {
                System.out.println("Warning: Things may not work properly if tags.dat is not imported!");
            }

            if (args.length < 8) {
                System.out.println("Warning: You may have missed one or more data files!");
            }
        }

        Populate pop = new Populate();
        pop.run(args);
    }

}
