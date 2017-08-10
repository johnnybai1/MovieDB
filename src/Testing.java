import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by Johnny on 11/18/16.
 */
public class Testing {

    public static void main(String[] args) throws Exception{
        String test = "1234";
        System.out.println(test.matches("[0-9]+") && test.length() == 4);
        Date date = new Date(2202272000000L);
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy");
        System.out.println(sdf.format(date).toString());
    }
}
