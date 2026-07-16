package com.example.mission.controller;
import com.example.mission.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class MyController {

    @Autowired
    private RestTemplate restTemplate;

    @GetMapping("/call-api")
    @ResponseBody
    public String callExternalApi(@RequestParam String city) {
        // 调用外部API
        String url = "https://api.seniverse.com/v3/weather/now.json?key=SLiqyqp-myXKw1e2J" +
                "&" +
                "location=" +city +"&language=zh-Hans&unit=c";
        String response = restTemplate.getForObject(url, String.class);
        if(response == null){
            return  String.valueOf(new BusinessException(400 ,"你输入的城市有误，请重新输入"));
        }
        return response ;
    }
}
