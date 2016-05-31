package gpig.group2.de.poiredeploy;

import co.j6mes.infra.srf.query.QueryResponse;
import co.j6mes.infra.srf.query.ServiceQuery;
import co.j6mes.infra.srf.query.SimpleServiceQuery;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gpig.group2.models.drone.request.RequestMessage;
import gpig.group2.models.drone.request.Task;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.fluent.Content;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gavaghan.geodesy.Ellipsoid;
import org.gavaghan.geodesy.GeodeticCalculator;
import org.gavaghan.geodesy.GlobalCoordinates;
import org.geojson.*;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

/**
 * Created by james on 29/05/2016.
 */
public class RedeployThread implements Runnable {


    public static void main(String args[]) throws InterruptedException {

        RedeployThread rd =new RedeployThread();

        rd.run();


    }


    private Logger log = LogManager.getLogger(RedeployThread.class);
    public final RedeployThread tthis = this;
    private String path = "";
    private boolean connectionUp;


    private List<Integer> tasks = new ArrayList<>();
    private Map<Integer,Date> completedTasks = new HashMap<>();

    @Override
    public void run() {
        connectionUp = false;

        ServiceQuery sq = new SimpleServiceQuery();

        QueryResponse qr = sq.query("c2","maps");
        if(qr.Path!=null) {
            connectionUp = true;
            path = "http://"+qr.IP+":"+qr.Port+"/"+qr.Path;
        }


        String url = "";


        Integer[] lids = new Integer[] {2,3,5};
        for(Integer lid : lids) {

            url = path + "layers/" + lid;


            try {

                Content cnt = Request.Get(url).execute().returnContent();
                log.debug("Got POIs from C2: " + cnt.asString());

                FeatureCollection featureCollection =
                        new ObjectMapper().readValue(cnt.asStream(), FeatureCollection.class);

                for (Feature f : featureCollection) {


                    if (f.getGeometry() instanceof Point) {
                        log.debug("Got point geometry");

                        Point p = (Point) f.getGeometry();


                        long R = 6378137;

                        //offsets in meters
                        int dn = 100;
                        int de = 100;

                        //Coordinate offsets in radians
                        double dLat = dn / (R * Math.cos(Math.PI * p.getCoordinates().getLatitude() / 180));
                        double dLon = de / (R * Math.cos(Math.PI * p.getCoordinates().getLatitude() / 180));

                        //OffsetPosition, decimal degrees
                        double maxLat = p.getCoordinates().getLatitude() + dLat * 180 / Math.PI;
                        double maxLong = p.getCoordinates().getLongitude() + dLon * 180 / Math.PI;

                        double minLat = p.getCoordinates().getLatitude() - dLat * 180 / Math.PI;
                        double minLong = p.getCoordinates().getLongitude() - dLon * 180 / Math.PI;


                        List<LngLatAlt> llas = new ArrayList<>();

                        {
                            LngLatAlt lla = new LngLatAlt();
                            lla.setLatitude(maxLat);
                            lla.setLongitude(maxLong);
                            llas.add(lla);
                        }

                        {
                            LngLatAlt lla = new LngLatAlt();
                            lla.setLatitude(maxLat);
                            lla.setLongitude(minLong);
                            llas.add(lla);
                        }

                        {
                            LngLatAlt lla = new LngLatAlt();
                            lla.setLatitude(minLat);
                            lla.setLongitude(minLong);
                            llas.add(lla);
                        }

                        {
                            LngLatAlt lla = new LngLatAlt();
                            lla.setLatitude(minLat);
                            lla.setLongitude(maxLong);
                            llas.add(lla);
                        }

                        {
                            LngLatAlt lla = new LngLatAlt();
                            lla.setLatitude(maxLat);
                            lla.setLongitude(maxLong);
                            llas.add(lla);
                        }


                        ObjectMapper mapper = new ObjectMapper();


                        StringWriter sw = new StringWriter();
                        Polygon poly = new Polygon();
                        poly.setExteriorRing(llas);
                        Feature newFeat = new Feature();
                        newFeat.setGeometry(poly);
                        mapper.writeValue(sw, newFeat);

                        System.out.println(sw.getBuffer().toString());

                        System.out.println(Request.Post(path + "deployAreas/create")
                                .useExpectContinue()
                                .bodyString(sw.getBuffer().toString(), ContentType.APPLICATION_JSON)
                                .execute().returnContent().asString());


                    }


                }


            } catch (JsonParseException e1) {
                e1.printStackTrace();
            } catch (JsonGenerationException e1) {
                e1.printStackTrace();
            } catch (ClientProtocolException e1) {
                e1.printStackTrace();
            } catch (JsonMappingException e1) {
                e1.printStackTrace();
            } catch (IOException e1) {
                e1.printStackTrace();
            }


        }




    }


    public void getDeploymentAreas() {
        String url = "";
        synchronized (tthis) {
            while(!connectionUp) {
                try {
                    tthis.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            url = path+"deployAreas";
        }

        try {

            Content cnt = Request.Get(url).execute().returnContent();
            log.debug("Got response from C2: " + cnt.asString());

            JAXBContext jc = JAXBContext.newInstance(RequestMessage.class);
            Unmarshaller u = jc.createUnmarshaller();

            RequestMessage rm = (RequestMessage) u.unmarshal(cnt.asStream());

            log.info("Unmarshalled C2 Response OK");


            if(rm.getTasksX()!=null && rm.getTasksX().size()>0) {


                for(Task t : rm.getTasksX()) {

                    tasks.add(t.getIdX());

                }

                for(Integer id : tasks) {


                    if(!taskIdExistsIn(id,rm.getTasksX()) && !completedTasks.keySet().contains(id)) {
                        synchronized (tthis) {
                            completedTasks.put(id,new Date());
                        }

                    }

                }

            }










        }catch (Exception ex) {
            log.error("ex Code sendPut: " + ex);
            log.error("url:" + url);
        } finally {
        }



    }

    private boolean taskIdExistsIn(Integer id, Collection<Task> tasksX) {
        for(Task t :tasksX) {

            if(t.getIdX().equals(id)) {

                return true;

            }

        }

        return false;

    }

    public void reactivateDeployments() {

    }


}
