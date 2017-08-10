import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.StringJoiner;

/**
 * GUI program to allow user to query data from database
 * 1. List of movie genres
 * 2. Countries where movies are produced
 * 3. Movies' casts (actor/actress/director): name directly entered in a text field or searched from a list
 * 4. Avg. rating of a movie: avg. of Rotten tomatoes all critics, top critics, and audience ratings
 * 5. Number of ratings: avg. of Rotten tomatoes all critics', top critics', and audiences' number of reviews
 * 6. Movie year
 * 7. Users information: user_id, user's rating of a movie, time stamps for tag/rating
 * 8. List of results
 *
 * Basic assumptions/run operation
 * 1. If multiple genres are selected, refer to ANY/ALL
 * 2. If multiple countries are selected, they are taken in disjunction (OR)
 * 3. If multiple actors/actresses are selected, refer to ANY/ALL
 * 4. If both movie rating and count are specified, they are taken in conjunction (AND)
 * 5. The year(s) specified in Movie Year are inclusive (<= or >=)
 * 6. If multiple fields in Users' rating are specified, refer to ANY/ALL
 * 7. If multiple tags are specified, refer to ANY/ALL
 * 8. Depending on the run option specified (AND or OR), the query will take the conjunction or
 *    disjunction of each section (genre, country, director, actor/actresses, movie ratings,
 *    movie year, and user's tags and ratings)
 *    Note: Selecting AND or OR does not affect how the values are taken within a section
 */
public class hw3 {

    public static void main(String[] args) {
        new hw3();
    }

    // Our query should always return the following columns:
    // 1. Title: movies.title
    // 2. Year: movies.year
    // 3. Country: movie_countries.country
    // 4. CriticsRating: (movies.rtAllCriticsRating + movies.rtTopCriticsRating) / 2
    // 5. NumCriticsReview: movies.rtAllCriticsNumReviews + movies.rtTopCriticsNumReviews
    // 6. AudienceRating: movies.rtAudienceRating
    // 7. NumAudienceReviews: movies.rtAudienceNumReviews
    // 8. Genre: movie_genres.genre
    // 9. Users' tags: tags.value where tags.id = user_taggedmovies.id
    // Our select clause never changes!
    public static final String SELECT = "SELECT \ntitle, year, country, critics_Rating, critics_NumReviews, " +
            "audience_Rating, audience_NumReviews,\n" +
            "LISTAGG(case when g = 1 THEN genre END, ', ') WITHIN GROUP (ORDER BY genre) as genres,\n" +
            "LISTAGG(case when t = 1 THEN tag END, ', ') WITHIN GROUP (ORDER BY tag) as tags\n" +
            "FROM\n(\n\tSELECT\n\tM.movie_id as id, M.title as title,\n\tMG.genre as genre,\n\tT.value as tag,\n" +
            "\tM.year as year,\n\tMC.country as country,\n" +
            "\tROUND((M.rtAllCriticsRating + M.rtTopCriticsRating)/2, 1) as critics_Rating,\n" +
            "\tM.rtAllCriticsNumReviews + M.rtTopCriticsNumReviews as critics_NumReviews,\n" +
            "\tM.rtAudienceRating as audience_Rating,\n\tM.rtAudienceNumReviews as audience_NumReviews,\n" +
            "\trow_number() over (partition by M.movie_id, MG.genre order by MG.genre) as g,\n" +
            "\trow_number() over (partition by M.movie_id, T.value order by T.value) as t";
    // Our base FROM clause
    public static String FROM = "\n\tFROM\n" +
            "\tmovies M left join movie_genres MG on M.movie_id = MG.movie_id\n" +
            "\tleft join movie_genres MG1 on M.movie_id = MG1.movie_id\n" +
            "\tleft join user_taggedmovies UTM on M.movie_id = UTM.movie_id\n" +
            "\tleft join movie_countries MC on M.movie_id = MC.movie_id\n" +
            "\tleft join tags T on UTM.tag_id = T.tag_id";
    // Our WHERE clause does change, but this variable never does
    public static final String GROUP = "\n)\nGROUP BY id, title, year, country, critics_Rating, " +
            "critics_NumReviews, audience_Rating, audience_NumReviews";

    // Sets to store selected attributes for querying
    private HashSet<String> selectedGenres = new HashSet<>();
    private HashSet<String> selectedCountries = new HashSet<>();
    private HashSet<String> selectedActors = new HashSet<>();

    // ------------------------------------------------------------------------
    // Below are the variables pertaining to the GUI
    private JFrame frame; // Main application frame
    private JFrame directorSearchFrame; // Directors search frame
    private JFrame actorSearchFrame; // Actors search frame

    // Options of genres: ANY or ALL
    private JRadioButton genreAny; // Match any of the genres selected
    private JRadioButton genreAll; // Match all of the genres selected

    // Options of actors: ANY or ALL
    private JRadioButton actorAny;
    private JRadioButton actorAll;

    // Need to extract text from director field when querying
    private JTextField directorField;
    private JTextField actorField;
    private JList selectedActorsList;

    // Extract comparison operators and values when we perform our query
    private JTextField ratingValue;
    private JTextField ratingCount;
    private JComboBox<String> movieRatingsCompare;
    private JComboBox<String> movieRatingsCountCompare;

    // Extract from and to movie years when we perform our query
    private JTextField fromMovieYear;
    private JTextField toMovieYear;

    // Extract Users' ratings and tags when we perform our query
    private JTextField userID;
    private JTextField userFromDate;
    private JTextField userToDate;
    private JComboBox<String> userRatingCompare;
    private JTextField userRatingValue;
    private JTextField tagText;
    // Options for users' ratings: ANY or ALL
    private JRadioButton userAny; // Match any of the search criteria for users
    private JRadioButton userAll; // Match all of the search criteria for users
    // Options for tags: ANY or ALL
    private JRadioButton tagAny; // Match any of the tags specified
    private JRadioButton tagAll; // Match all of the tags specified

    // Options for AND/OR
    private JRadioButton andButton;
    private JRadioButton orButton;

    // Query and Results
    private JTextArea queryText;
    private JTextArea resultsText;

    private String[] comparisonOperators =
            {"=,<,>,≤,≥", "=", "<", ">", "<=", ">="}; // index0 is "None"

    private Connection con;

    public hw3() {
        try {
            con = openConnection(); // Application only launches if our connection succeeds
            DatabaseMetaData dbmd = con.getMetaData(); // Get DbMD to get information
            initGUI();
        } catch (SQLException e) {
            while (e != null) {
                System.out.println("Message: " + e.getMessage());
                System.out.println("SQLState: " + e.getSQLState());
                System.out.println("Vendor Error: " + e.getErrorCode());
                e = e.getNextException();
            }
        } catch (ClassNotFoundException e) {
            System.err.println("Cannot find the database driver");
        }
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
            System.out.println("Connection.txt not found, using values defined in hw3.java");
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
        } catch (SQLException e) {
            System.err.println("Cannot close connection: " + e.getMessage());
        }
    }

    // Return a list of distinct values of a column from a table
    private ArrayList<String> getDistinctValues(Connection con, String table, String column) throws SQLException {
        Statement s = con.createStatement();
        String sql = "SELECT DISTINCT " + column + " FROM " + table + " ORDER BY " + column + " ASC";
        s.execute(sql);
        ResultSet rs = s.getResultSet();
        ResultSetMetaData rsmd = rs.getMetaData();
        ArrayList<String> result = new ArrayList<>();
        while (rs.next()) {
            result.add(rs.getString(1));
        }
        rs.close();
        return result;
    }


    /**
     * Parses through all fields and generates a query statement.
     * Basically want to find movie_id that satisfies search criteria
     */
    private void runQuery() throws SQLException {
        reset();
        String sql = buildQuery();
        System.out.println(sql);
        queryText.setText(sql); // Set the query text area
        Statement s = con.createStatement();
        s.execute(sql);
        ResultSet rs = s.getResultSet();
        ResultSetMetaData rsmd = rs.getMetaData();
        int colCount = rsmd.getColumnCount();
        String[] colNames = new String[colCount]; // Get column names
        for (int i = 0; i < colCount; i++) {
            colNames[i] = rsmd.getColumnName(i+1);
        }
        int resultNum = 1;
        while (rs.next()) {
            resultsText.append("RESULT " + resultNum + "\n");
            for (int i = 0; i < colCount; i++) {
                String padded = String.format("%1$-25s", colNames[i]);
                resultsText.append(padded + rs.getObject(i+1) + "\n");
            }
            resultNum++;
        }
    }


    //=========================================================================
    // The below methods parse through the GUI fields and builds our query
    private String buildQuery() {
        String op;
        if (andButton.isSelected()) {
            op = " AND ";
        }
        else op = " OR ";
        String[] sections = {
                processGenres(),
                processCountries(),
                processDirector(),
                processActors(),
                processMovieRatings(),
                processMovieCounts(),
                processMovieYear(),
                processUserRatings(),
                processTags()
        };
        boolean empty = true;
        StringJoiner sj = new StringJoiner(op, "\n\tWHERE", "");
        for (int i = 0; i < sections.length; i++) {
            // our processXxx() functions return "_AND_()" or "_OR_()"
            // So long as the length of the string is greater than 7,
            // the user has selected an attribute for section Xxx
            if (sections[i] != null) {
                sj.add("\n" + sections[i]);
                empty = false;
            }
        }
        if (empty) {
            return SELECT + FROM + GROUP;
        }
        return SELECT + FROM + sj.toString() + GROUP;
    }

    private String processGenres() {
        if (selectedGenres.isEmpty()) {
            return null;
        }
        if (genreAll.isSelected() && selectedGenres.size() > 1) {
            // Match ALL of the genres selected
            StringJoiner sj = new StringJoiner("\n\t\tINTERSECT\n", "", ")");
            String s = "\t\tSELECT movie_id\n\t\tFROM movie_genres\n\t\tWHERE genre = ";
            for (String genre : selectedGenres) {
                sj.add(s + "'" + genre + "'");
            }
            return "\t(M.movie_id in\n\t\t(\n" + sj.toString() + ")";
        }
        else {
            // Match ANY of the genres selected
            StringJoiner sj = new StringJoiner(",", "(", ")");
            for (String genre : selectedGenres) {
                sj.add("'" + genre + "'");
            }
            return "\t(MG1.genre in " + sj.toString() + ")";
        }
    }

    // Assumption: select movies that matches ANY country chosen since
    // each movie is associated with at most one country
    private String processCountries() {
        if (selectedCountries.isEmpty()) {
            return null;
        }
        StringJoiner sj = new StringJoiner(",", "(", ")");
        for (String country : selectedCountries) {
            sj.add("'" + country + "'");
        }
        return "\t(MC.country = ANY" + sj.toString() + ")";
    }

    private String processDirector() {
        String director = directorField.getText();
        if (director == null || director.isEmpty()) {
            return null;
        }
        else {
            FROM += "\n\tleft join movie_directors MD on M.movie_id = MD.movie_id";
            return "\t(MD.director_name = '" + director + "')";
        }
    }

    // Assumption: select movies that matches ALL actors/actresses chosen
    private String processActors() {
        if (selectedActors.isEmpty()) {
            return null;
        }
        FROM += "\n\tleft join movie_actors MA on M.movie_id = MA.movie_id";
        if (actorAll.isSelected() && selectedActors.size() > 1) {
            // Match ALL of the actors selected
            StringJoiner sj = new StringJoiner("\n\t\tINTERSECT\n", "", ")");
            String s = "\t\tSELECT movie_id\n\t\tFROM movie_actors\n\t\tWHERE actor_name = ";
            for (String actor : selectedActors) {
                sj.add(s + "'" + actor + "'");
            }
            return "\t(M.movie_id in\n\t\t(\n" + sj.toString() + ")";
        }
        else {
            // Match ANY of the actors selected
            StringJoiner sj = new StringJoiner(",", "(", ")");
            for (String actor : selectedActors) {
                sj.add("'" + actor + "'");
            }
            return "\t(MA.actor_name in " + sj.toString() + ")";
        }
    }

    // Assumption: If user does not select comparison operator from combo box, use '='
    private String processMovieRatings() {
        String rating = ratingValue.getText();
        if (rating == null || rating.isEmpty()) {
            return null;
        }
        String op;
        if (movieRatingsCompare.getSelectedIndex() == 0) {
            op = "=";
        }
        else op = (String) movieRatingsCompare.getSelectedItem();
        return "\t(ROUND((M.rtAllCriticsRating + M.rtTopCriticsRating + M.rtAudienceRating) / 3, 1) " +
                op + " " + rating + ")";
    }

    // Assumption: If user does not select comparison operator from combo box, use '='
    private String processMovieCounts() {
        String count = ratingCount.getText();
        if (count == null || count.isEmpty()) {
            return null;
        }
        String op;
        if (movieRatingsCountCompare.getSelectedIndex() == 0) {
            op = "=";
        }
        else op = (String) movieRatingsCountCompare.getSelectedItem();
        return "\t(ROUND((M.rtAllCriticsNumReviews + M.rtTopCriticsNumReviews + M.rtAudienceNumReviews) / 3, 1) "
                + op + " " + count + ")";
    }

    // Assumption: From and to year values inputted are INCLUSIVE
    private String processMovieYear() {
        StringJoiner sj = new StringJoiner(" AND ", "\t(", ")");
        String from = fromMovieYear.getText();
        String to = toMovieYear.getText();
        if ((from == null && to == null) || (from.isEmpty() && to.isEmpty())) {
            return null;
        }
        if (!from.isEmpty()) {
            sj.add("M.year >= " + from);
        }
        if (!to.isEmpty()) {
            sj.add("M.year <= " + to);
        }
        return sj.toString();
    }

    private String processUserID() {
        String user = userID.getText();
        if (user == null || user.isEmpty()) {
            return null;
        }
        return "\t(URM.user_id = " + user + ")";
    }

    private String processFromDate() {
        String from = userFromDate.getText();
        if (from == null || from.isEmpty()) {
            return null;
        }
        return "(URM.ts > TO_DATE('" + from + "', 'MM/DD/YYYY'))";
    }

    private String processToDate() {
        String to = userToDate.getText();
        if (to == null || to.isEmpty()) {
            return null;
        }
        return "(URM.ts < TO_DATE('" + to + "', 'MM/DD/YYYY'))";
    }

    private String processDate() {
        /*
        return "\t(M.movie_id in\n\t\t(\n\t\tSELECT movie_id\n\t\tFROM user_ratedmovies" +
                "\n\t\tWHERE rating " + op + " " + rating + "))";
         */
        String from = processFromDate();
        String to = processToDate();
        String s = "\t(M.movie_id in\n\t\t(\n\t\tSELECT DISTINCT movie_id\n\t\tFROM user_ratedmovies" +
                "\n\t\tWHERE ";
        if (from == null && to == null) {
            return null;
        }
        if (from == null) {
            return s + to + "\n\t)";
        }
        if (to == null) {
            return s + from + "\n\t)";
        }
        return s + from + " AND " + to + "\n\t\t))";
    }


    private String processUserRatingValue() {
        String rating = userRatingValue.getText();
        if (rating == null || rating.isEmpty()) {
            return null;
        }
        String op;
        if (userRatingCompare.getSelectedIndex() == 0) {
            op = "=";
        }
        else op = (String) userRatingCompare.getSelectedItem();
        return "\t(URM.rating " + op + " " + rating + ")";
    }

    private String processTags() {
        String tags = tagText.getText();
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String[] values = tags.split(",");
        String s = "\t\tSELECT DISTINCT UTM.movie_id\n\t\tFROM user_taggedmovies UTM, tags T\n" +
                "\t\tWHERE UTM.tag_id = T.tag_id AND T.value = ";
        if (tagAll.isSelected() && values.length > 1) {
            StringJoiner sj = new StringJoiner("\n\t\tINTERSECT\n","",")");
            for (String tag : values) {
                // SELECT ... FROM ... WHERE ... AND T.value = 'tag'
                sj.add(s + "'" + tag + "'");
            }
            return "\t(M.movie_id in\n\t\t(\n" + sj.toString() + ")";
        }
        else {
            StringJoiner sj = new StringJoiner(",", "(", ")");
            for (String tag : values) {
                sj.add("'" + tag + "'");
            }
            return "\t(T.value in " + sj.toString() + ")";
        }
    }

    private String processUserRatings() {
        boolean empty = true;
        String[] fields =
                {
                        processUserID(),
                        processDate(),
                        processUserRatingValue()
                };
        String op;
        if (userAll.isSelected()) {
            op = " AND\n";
        }
        else op = " OR\n";
        StringJoiner sj = new StringJoiner(op);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != null) {
                empty = false;
                sj.add(fields[i]);
            }
        }
        if (empty) {
            return null;
        }
        FROM += ",\n\tuser_ratedmovies URM";
        return "\t(URM.movie_id = M.movie_id) AND\n" + sj.toString();
    }


    // Call after a query runs to clear all fields and data
    private void reset() {
        // Reset our query clause
        FROM = "\n\tFROM\n" +
                "\tmovies M left join movie_genres MG on M.movie_id = MG.movie_id\n" +
                "\tleft join movie_genres MG1 on M.movie_id = MG1.movie_id\n" +
                "\tleft join user_taggedmovies UTM on M.movie_id = UTM.movie_id\n" +
                "\tleft join movie_countries MC on M.movie_id = MC.movie_id\n" +
                "\tleft join tags T on UTM.tag_id = T.tag_id";
        // Clear the text area
        queryText.setText("");
        resultsText.setText("");
    }

    //=========================================================================
    // The methods below create our gui
    private void initGUI() throws SQLException {
        initFrame(); // Create parent frame
        ArrayList<String> directors = getDistinctValues(con, "movie_directors", "director_name");
        ArrayList<String> actors = getDistinctValues(con, "movie_actors", "actor_name");
        directorSearchFrame = createSearchFrame(directors, true);
        actorSearchFrame = createSearchFrame(actors, false);
        JPanel top = initAttributePane(getDistinctValues(con, "movie_genres", "genre"),
                getDistinctValues(con, "movie_countries", "country"));
        JPanel bot = initRunPane();
        frame.add(top);
        frame.add(bot);
        frame.pack();
        frame.setVisible(true);
    }

    private void initFrame() {
        frame = new JFrame("Movie DB");
        frame.setLayout(new GridLayout(0, 1, 0, 0));
        frame.setSize(1200, 800);
        frame.setVisible(true);
        frame.setFocusable(true);
        frame.setResizable(false);
        WindowListener onClose = new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                super.windowClosing(e);
                closeConnection(con);
                System.exit(0);
            }
        };
        frame.addWindowListener(onClose);
    }

    private JPanel initAttributePane(ArrayList<String> genres, ArrayList<String> countries) {
        JPanel panel = new JPanel(new FlowLayout());
        panel.setPreferredSize(new Dimension(1200, 400));
        panel.add(initGenrePane(genres));
        panel.add(initCountryPane(countries));
        panel.add(initDirectorCastPane());
        panel.add(initMovieRatingYearPane());
        panel.add(initUserTagsRatingsPane());
        return panel;
    }

    // Responsible for creating a list of checkboxes representing the movie genres
    private JPanel initGenrePane(ArrayList<String> genres) {
        JPanel panel = new JPanel();
        panel.setLayout(new GridBagLayout());
        panel.setPreferredSize(new Dimension(200, 400));
        panel.setBorder(BorderFactory.createTitledBorder(null, "Genres", TitledBorder.CENTER, TitledBorder.TOP));
        GridBagConstraints gbc = new GridBagConstraints();

        JPanel bp = new JPanel();
        bp.setLayout(new GridLayout(1, 0, 0, 0));
        bp.setPreferredSize(new Dimension(175, 50));
        genreAny = new JRadioButton("ANY");
        genreAll = new JRadioButton("ALL");
        genreAny.setSelected(true);
        ButtonGroup bg = new ButtonGroup();
        bg.add(genreAny);
        bg.add(genreAll);
        bp.add(genreAny);
        bp.add(genreAll);

        JScrollPane sp = createCheckboxList(genres, selectedGenres);
        sp.setPreferredSize(new Dimension(175, 300));

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(sp, gbc);
        gbc.gridy = 1;
        panel.add(bp, gbc);
        return panel;
    }

    // Responsible for creating a list of countries that movies were made in
    private JScrollPane initCountryPane(ArrayList<String> countries) {
        JScrollPane sp = createCheckboxList(countries, selectedCountries);
        sp.setPreferredSize(new Dimension(250, 400));
        sp.setBorder(BorderFactory.createTitledBorder(null, "Countries", TitledBorder.CENTER, TitledBorder.TOP));
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return sp;
    }

    // Responsible for setting up the actors/actresses and directors panel. We will allow multiple
    // actors/actresses to be selected, but only one director. Allow selection of AND/OR between each actor/actress
    // and between the actors/actresses and director.
    private JPanel initDirectorCastPane() {
        // Need a new panel with GridBagLayout
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(250, 400));
        panel.setBorder(BorderFactory.createTitledBorder(null, "Director/Cast", TitledBorder.CENTER, TitledBorder.TOP));
        GridBagConstraints gbc = new GridBagConstraints();
        // Create our Director portion
        // 1. Label
        JLabel directorLabel = new JLabel("Director");
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(directorLabel, gbc);
        // 2. Text field for user to input a director's name
        directorField = new JTextField();
        directorField.setPreferredSize(new Dimension(150, 25));
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(directorField, gbc);
        // 3. Find button to let user choose from list of directors
        JButton findDirector = createButtonToPopup(directorSearchFrame);
        gbc.gridx = 1;
        gbc.gridy = 1;
        panel.add(findDirector, gbc);

        // Create our Cast portion
        // 1. Label
        JLabel actorLabel = new JLabel("Actor/Actress");
        gbc.gridx = 0;
        gbc.gridy = 2;
        panel.add(actorLabel, gbc);
        // 2. Text field for user to input an actor's name
        actorField = new JTextField();
        actorField.setPreferredSize(new Dimension(150, 25));
        gbc.gridx = 0;
        gbc.gridy = 3;
        panel.add(actorField, gbc);
        // 3. Find button to let user choose from list of actors
        JButton findActor = createButtonToPopup(actorSearchFrame);
        gbc.gridx = 1;
        gbc.gridy = 3;
        panel.add(findActor, gbc);
        // 4. + button to let add user-entered actors
        JButton add = new JButton("+");
        add.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectedActors.add(actorField.getText());
                DefaultListModel<String> model = new DefaultListModel<>();
                for (String item : selectedActors) {
                    model.addElement(item);
                }
                selectedActorsList.setModel(model);
                selectedActorsList.updateUI();
                actorField.setText("");
            }
        });
        add.setPreferredSize(new Dimension(25, 25));
        gbc.gridx = 2;
        gbc.gridy = 3;
        panel.add(add, gbc);
        // 5. Label for selected actors
        JLabel actorsListLabel = new JLabel("Selected actors/actresses");
        gbc.gridx = 0;
        gbc.gridy = 4;
        panel.add(actorsListLabel, gbc);
        // 6. A list of selected actors (dynamic)
        selectedActorsList = new JList<String>();
        selectedActorsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane selectedActorsListPane = new JScrollPane(selectedActorsList);
        selectedActorsListPane.setPreferredSize(new Dimension(150, 175));
        gbc.gridx = 0;
        gbc.gridy = 5;
        panel.add(selectedActorsListPane, gbc);
        // 7. '-' button to let user remove actors
        JButton rm = new JButton("-");
        rm.setPreferredSize(new Dimension(25, 25));
        rm.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectedActors.removeAll(selectedActorsList.getSelectedValuesList());
                DefaultListModel<String> model = new DefaultListModel<>();
                for (String item : selectedActors) {
                    model.addElement(item);
                }
                selectedActorsList.setModel(model);
                selectedActorsList.updateUI();
            }
        });
        gbc.gridx = 1;
        gbc.gridy = 5;
        panel.add(rm, gbc);

        JPanel bp = new JPanel();
        bp.setLayout(new GridLayout(1, 0, 0, 0));
        bp.setPreferredSize(new Dimension(150, 50));
        actorAny = new JRadioButton("ANY");
        actorAny.setPreferredSize(new Dimension(50, 45));
        actorAll = new JRadioButton("ALL");
        actorAll.setPreferredSize(new Dimension(50, 45));
        actorAny.setSelected(true);
        ButtonGroup bg = new ButtonGroup();
        bg.add(actorAny);
        bg.add(actorAll);
        bp.add(actorAny);
        bp.add(actorAll);
        gbc.gridx = 0;
        gbc.gridy = 6;
        panel.add(bp, gbc);

        //7. RadioButton to let user choose ANY or ALL
        return panel;
    }

    private JPanel initMovieRatingYearPane() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(200, 400));
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JPanel rating = initMovieRatingPane();
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(rating, gbc);
        JPanel year = initMovieYearPane();
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(year, gbc);

        return panel;
    }

    private JPanel initMovieRatingPane() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(200, 270));
        panel.setBorder(BorderFactory.createTitledBorder(null, "Movie Ratings", TitledBorder.CENTER, TitledBorder.TOP));
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;

        JLabel ratingLabel = new JLabel("Ratings");
        ratingLabel.setPreferredSize(new Dimension(50, 25));
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(ratingLabel, gbc);
        movieRatingsCompare = new JComboBox<>(comparisonOperators);
        movieRatingsCompare.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 0;
        panel.add(movieRatingsCompare, gbc);
        JLabel ratingValueLabel = new JLabel("Value");
        ratingValueLabel.setPreferredSize(new Dimension(50, 25));
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(ratingValueLabel, gbc);
        ratingValue = new JTextField();
        ratingValue.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 1;
        panel.add(ratingValue, gbc);

        JLabel numRatingsLabel = new JLabel("Count");
        numRatingsLabel.setPreferredSize(new Dimension(50, 25));
        gbc.gridx = 0;
        gbc.gridy = 2;
        panel.add(numRatingsLabel, gbc);
        movieRatingsCountCompare = new JComboBox<>(comparisonOperators);
        movieRatingsCountCompare.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 2;
        panel.add(movieRatingsCountCompare, gbc);
        JLabel ratingCountLabel = new JLabel("Value");
        ratingCountLabel.setPreferredSize(new Dimension(50, 25));
        gbc.gridx = 0;
        gbc.gridy = 3;
        panel.add(ratingCountLabel, gbc);
        ratingCount = new JTextField();
        ratingCount.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 3;
        panel.add(ratingCount, gbc);
        return panel;
    }

    private JPanel initMovieYearPane() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(200, 125));
        panel.setBorder(BorderFactory.createTitledBorder(null, "Movie Year", TitledBorder.CENTER, TitledBorder.TOP));
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;

        JLabel from = new JLabel("From");
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(from, gbc);
        fromMovieYear = new JTextField();
        fromMovieYear.setPreferredSize(new Dimension(100, 25));
        gbc.gridx = 1;
        panel.add(fromMovieYear, gbc);

        JLabel to = new JLabel("To");
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(to, gbc);
        toMovieYear = new JTextField();
        toMovieYear.setPreferredSize(new Dimension(100, 25));
        gbc.gridx = 1;
        panel.add(toMovieYear, gbc);
        return panel;
    }

    private JPanel initUserTagsRatingsPane() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(250, 400));
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        JPanel userPanel = new JPanel();
        userPanel.setPreferredSize(new Dimension(250, 225));
        userPanel.setLayout(new GridBagLayout());
        userPanel.setBorder(BorderFactory.createTitledBorder(null, "Users' Ratings", TitledBorder.CENTER, TitledBorder.TOP));
        // User ID Label
        JLabel userIDLabel = new JLabel("User ID");
        gbc.gridx = 0;
        gbc.gridy = 0;
        userPanel.add(userIDLabel, gbc);
        // User ID Field
        userID = new JTextField();
        userID.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 0;
        userPanel.add(userID, gbc);

        // Date format label
        JLabel format = new JLabel("Date: MM/DD/YYYY");
        gbc.gridx = 1;
        gbc.gridy = 1;
        userPanel.add(format, gbc);
        // From date label
        JLabel fromLabel = new JLabel("From date");
        gbc.gridx = 0;
        gbc.gridy = 2;
        userPanel.add(fromLabel, gbc);
        // From date field
        userFromDate = new JTextField();
        userFromDate.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 2;
        userPanel.add(userFromDate, gbc);

        // To date label
        JLabel toLabel = new JLabel("To date");
        gbc.gridx = 0;
        gbc.gridy = 3;
        userPanel.add(toLabel, gbc);
        // To date field
        userToDate = new JTextField();
        userToDate.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 3;
        userPanel.add(userToDate, gbc);

        // User rating comparison label
        JLabel userRatingCompareLabel = new JLabel("Rating");
        gbc.gridx = 0;
        gbc.gridy = 4;
        userPanel.add(userRatingCompareLabel, gbc);
//         User rating compare operator box
        userRatingCompare = new JComboBox<>(comparisonOperators);
        gbc.gridx = 1;
        gbc.gridy = 4;
        userPanel.add(userRatingCompare, gbc);
//         User rating value label
        JLabel userRatingValueLabel = new JLabel("Value");
        gbc.gridx = 0;
        gbc.gridy = 5;
        userPanel.add(userRatingValueLabel, gbc);
//         User rating value field
        userRatingValue = new JTextField();
        userRatingValue.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 5;
        userPanel.add(userRatingValue, gbc);
//        User rating options
        userAny = new JRadioButton("ANY");
        userAll = new JRadioButton("ALL");
        userAll.setSelected(true);
        ButtonGroup bg = new ButtonGroup();
        bg.add(userAny);
        bg.add(userAll);
        gbc.gridx = 0;
        gbc.gridy = 6;
        userPanel.add(userAny, gbc);
        gbc.gridx = 1;
        userPanel.add(userAll, gbc);


        JPanel tagPanel = new JPanel();
        tagPanel.setPreferredSize(new Dimension(250, 165));
        tagPanel.setLayout(new GridBagLayout());
        tagPanel.setBorder(BorderFactory.createTitledBorder(null, "Users' Tags", TitledBorder.CENTER, TitledBorder.TOP));
//        Tag format
        JLabel tagFormat = new JLabel("<html>Separate multiple values with ','" +
                "<br>Example: t1,t2,t3</html>");
        tagFormat.setPreferredSize(new Dimension(125, 50));
        gbc.gridx = 1;
        gbc.gridy = 0;
        tagPanel.add(tagFormat,gbc);
//        User tag label
        JLabel tagLabel = new JLabel("Tags");
        gbc.gridx = 0;
        gbc.gridy = 1;
        tagPanel.add(tagLabel, gbc);
//        User tag text
        tagText = new JTextField();
        tagText.setPreferredSize(new Dimension(125, 25));
        gbc.gridx = 1;
        gbc.gridy = 1;
        tagPanel.add(tagText, gbc);
//        User tag options
        tagAny = new JRadioButton("ANY");
        tagAll = new JRadioButton("ALL");
        tagAny.setSelected(true);
        ButtonGroup bg1 = new ButtonGroup();
        bg1.add(tagAny);
        bg1.add(tagAll);
        gbc.gridx = 0;
        gbc.gridy = 2;
        tagPanel.add(tagAny, gbc);
        gbc.gridx = 1;
        tagPanel.add(tagAll, gbc);

//        Add user and tag panels to main panel
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(userPanel, gbc);
        gbc.gridy = 1;
        panel.add(tagPanel, gbc);



        return panel;
    }

    private JPanel initRunPane() throws SQLException {
        JPanel panel = new JPanel(new FlowLayout());
        panel.setPreferredSize(new Dimension(1200, 400));
        panel.add(initQueryPane());
        panel.add(initResultsPane());
        panel.add(initOptionsPane());
        return panel;
    }

    private JScrollPane initQueryPane() {
        queryText = new JTextArea();
        queryText.setFont(new Font("monospaced", Font.PLAIN, 12));
        queryText.setEditable(false); // Do not let user type in stuff
        JScrollPane sp = new JScrollPane(queryText);
        sp.setBorder(BorderFactory.createTitledBorder(null, "Querying", TitledBorder.LEFT, TitledBorder.TOP));
        sp.setPreferredSize(new Dimension(550, 400));
        return sp;
    }

    private JScrollPane initResultsPane() {
        resultsText = new JTextArea();
        resultsText.setFont(new Font("monospaced", Font.PLAIN, 12));
        resultsText.setEditable(false); // Do not let user type in stuff
        JScrollPane sp = new JScrollPane(resultsText);
        sp.setBorder(BorderFactory.createTitledBorder(null, "Results", TitledBorder.LEFT, TitledBorder.TOP));
        sp.setPreferredSize(new Dimension(550, 400));
        return sp;
    }

    private JPanel initOptionsPane() throws SQLException {
        JPanel panel = new JPanel(new GridLayout(3, 0, 0, 0));
        panel.setPreferredSize(new Dimension(75, 400));
        panel.setBorder(BorderFactory.createTitledBorder(null, "<html>Run<br>Options</html>", TitledBorder.LEFT, TitledBorder.TOP));
        andButton = new JRadioButton("AND");
        andButton.setPreferredSize(new Dimension(75, 100));
        andButton.setSelected(true); // Default to AND
        orButton = new JRadioButton("OR");
        orButton.setPreferredSize(new Dimension(75, 100));
        ButtonGroup bg = new ButtonGroup();
        bg.add(andButton);
        bg.add(orButton);

        JButton runButton = new JButton("RUN");
        runButton.setPreferredSize(new Dimension(50, 50));
        runButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    runQuery();
                }
                catch (SQLException ex) {
                    while (ex != null) {
                        System.out.println("Message: " + ex.getMessage());
                        System.out.println("SQLState: " + ex.getSQLState());
                        System.out.println("Vendor Error: " + ex.getErrorCode());
                        ex = ex.getNextException();
                    }
                }
            }
        });

        panel.add(andButton);
        panel.add(orButton);
        panel.add(runButton);
        return panel;
    }

    // Method to create a generic checkbox list that stores checked items in specified set
    private JScrollPane createCheckboxList(ArrayList<String> list, HashSet<String> selected) {
        CheckboxListItem[] items = new CheckboxListItem[list.size()];
        for (int i = 0; i < list.size(); i++) {
            items[i] = new CheckboxListItem((list.get(i)));
        }
        JList<CheckboxListItem> l = new JList<>(items);
        l.setCellRenderer(new CheckboxListRenderer());
        l.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        l.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                JList<CheckboxListItem> list = (JList<CheckboxListItem>) event.getSource();
                int index = list.locationToIndex(event.getPoint());
                CheckboxListItem item = (CheckboxListItem) list.getModel().getElementAt(index);
                item.setSelected(!item.isSelected());
                if (item.isSelected()) {
                    // Add to selected genres if selected
                    selected.add(item.toString());
                }
                else {
                    // Remove from selected genres if unselected
                    selected.remove(item.toString());
                }
                list.repaint(list.getCellBounds(index, index));
            }
        });
        return new JScrollPane(l);
    }

    private JButton createButtonToPopup(JFrame f) {
        JButton button = new JButton("F");
        button.setPreferredSize(new Dimension(25, 25));
        button.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                f.setVisible(true);
                frame.setEnabled(false);
                frame.setFocusable(false);
            }
        });
        return button;
    }

    // Creates a list of selectable items for director/actors, depending on the forDirector boolean
    private JFrame createSearchFrame(ArrayList<String> list, boolean forDirector) {
        JFrame searchFrame = new JFrame();
        searchFrame.setLayout(new FlowLayout());
        searchFrame.setSize(250, 300);
        searchFrame.setResizable(false);
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            items[i] = list.get(i);
        }
        JList<String> l = new JList<>(items);
        if (forDirector) {
            searchFrame.setTitle("Directors");
            l.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        }
        else {
            searchFrame.setTitle("Actors/Actresses");
            l.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        }
        JScrollPane listPane = new JScrollPane(l);
        listPane.setPreferredSize(new Dimension(225, 200));
        listPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        searchFrame.add(listPane);
        JButton select = new JButton("SELECT");
        select.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (forDirector) {
                    directorField.setText(l.getSelectedValue()); // extract this value when we query
                }
                else {
                    selectedActors.addAll(l.getSelectedValuesList());
                    DefaultListModel<String> model = new DefaultListModel<>();
                    for (String item : selectedActors) {
                        model.addElement(item);
                    }
                    selectedActorsList.setModel(model);
                    selectedActorsList.updateUI();
                }
                l.clearSelection();
                searchFrame.setVisible(false);
                frame.setFocusable(true);
                frame.setEnabled(true);
            }
        });
        WindowListener onClose = new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                super.windowClosing(e);
                frame.setFocusable(true);
                frame.setEnabled(true);
            }
        };
        searchFrame.addWindowListener(onClose);
        searchFrame.add(select);
        return searchFrame;
    }


    // Based off http://learn-java-by-example.com/java/add-checkbox-items-jlist/
    class CheckboxListItem {
        private String label;
        private boolean selected = false;

        public CheckboxListItem(String label) {
            this.label = label;
        }

        public boolean isSelected() {
            return selected;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        public String toString() {
            return label;
        }
    }

    // Based off http://learn-java-by-example.com/java/add-checkbox-items-jlist/
    class CheckboxListRenderer extends JCheckBox implements ListCellRenderer<CheckboxListItem> {
        @Override
        public Component getListCellRendererComponent(
                JList<? extends CheckboxListItem> list, CheckboxListItem value,
                int index, boolean selected, boolean cellFocus) {
            setEnabled(list.isEnabled());
            setSelected(value.isSelected());
            setText(value.toString());
            return this;
        }
    }

}


