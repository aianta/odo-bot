import ca.ualberta.odobot.semanticflow.ModelManager;
import ca.ualberta.odobot.semanticflow.Neo4JParser;
import ca.ualberta.odobot.semanticflow.StateParser;
import ca.ualberta.odobot.semanticflow.Utils;
import ca.ualberta.odobot.semanticflow.statemodel.Coordinate;
import ca.ualberta.odobot.semanticflow.statemodel.Graph;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class CoordinateMergeTest {
    private static final Logger log = LoggerFactory.getLogger(CoordinateMergeTest.class);

    private static final String TEST_HTML = """
            <div class="content ui-tabs ui-widget ui-widget-content ui-corner-all" id="edit_event_tabs">
  <ul class="tab_list ui-tabs-nav ui-helper-reset ui-helper-clearfix ui-widget-header ui-corner-all" role="tablist">
    <li class="ui-state-default ui-corner-top ui-tabs-active ui-state-active" role="tab" tabindex="0" aria-controls="edit_calendar_event_form_holder" aria-labelledby="ui-id-3" aria-selected="true"><a href="#edit_calendar_event_form_holder" class="edit_calendar_event_option ui-tabs-anchor" role="presentation" tabindex="-1" id="ui-id-3">Event</a></li>
    
    
    
    
  </ul>
  <div id="edit_calendar_event_form_holder" class="tab_holder clearfix ui-tabs-panel ui-widget-content ui-corner-bottom" aria-labelledby="ui-id-3" role="tabpanel" aria-expanded="true" aria-hidden="false"><form data-testid="calendar-event-form" class="fOyUs_bGBk" style="margin: 0.75rem;"><fieldset class="cWmNi_bGBk"><legend class="ergWt_bGBk"></legend><span class="cMIPy_bGBk"><span class="fxIji_bGBk fxIji_DpxJ fxIji_bWOh fxIji_NmrE fxIji_buDT fxIji_bBOa"><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ bNerA_eFno"><span class="efIdg_cLpc"><span class="cMIPy_bGBk"><span class="fxIji_bGBk fxIji_DpxJ fxIji_bWOh fxIji_NmrE fxIji_buDT fxIji_bBOa"><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ"><label for="TextInput__uLPubpukm6zb" class="cWmNi_bGBk"><span class="cMIPy_bGBk"><span class="fxIji_bGBk fxIji_DpxJ fxIji_bWOh fxIji_NmrE fxIji_buDT fxIji_bBOa"><span class="bNerA_bGBk bNerA_NmrE bNerA_dBtH bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span class="fCrpb_bGBk fCrpb_egrg">Title:</span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ bNerA_eFno"><span class="qBMHb_cSXm"><input id="TextInput__uLPubpukm6zb" class="qBMHb_cwos qBMHb_ycrn qBMHb_EMjX" placeholder="Input Event Title..." type="text" value=""></span></span></span></span></label></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span class="undefined bBhxr_ZftU"><label for="Selectable__uzmkKykemeP6" class="cWmNi_bGBk cWmNi_eXrk" style="width: 100%;"><span class="cMIPy_bGBk"><span class="fxIji_bGBk fxIji_DpxJ fxIji_bWOh fxIji_NmrE fxIji_buDT fxIji_bBOa"><span class="bNerA_bGBk bNerA_NmrE bNerA_dBtH bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span class="fCrpb_bGBk fCrpb_egrg">Date:</span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ bNerA_eFno"><span class="qBMHb_cSXm"><span wrap="wrap" direction="row" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_busO bDzpk_fZWR bDzpk_dyGy bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_zczv dJCgj_dfFp"><span direction="row" wrap="no-wrap" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_busO bDzpk_fZWR bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_zczv dJCgj_dfFp"><input id="Selectable__uzmkKykemeP6" aria-haspopup="listbox" aria-expanded="false" aria-describedby="Selectable__uzmkKykemeP6-description" data-testid="edit-calendar-event-form-date" role="combobox" aria-autocomplete="both" autocomplete="off" class="qBMHb_cwos qBMHb_ycrn qBMHb_EMjX" type="text" value="Wed, Jan 18, 2023" data-position-target="Popover__u7CLL2V3zaKU" width="100%"></span><span class="fOyUs_bGBk dJCgj_bGBk" style="padding: 0px 0.75rem 0px 0px;"><svg name="IconCalendarMonth" viewBox="0 0 1920 1920" rotate="0" style="width: 1em; height: 1em;" width="1em" height="1em" aria-hidden="true" role="presentation" focusable="false" class="dUOHu_bGBk dUOHu_drOs dUOHu_cRbP cGqzL_bGBk"><g role="presentation"><path d="M1411.8238,9.99999997e-05 C1442.9948,9.99999997e-05 1468.2938,25.2991 1468.2938,56.4711 L1468.2938,56.4711 L1468.2938,112.9411 L1637.7058,112.9411 C1731.1088,112.9411 1807.1178,188.9511 1807.1178,282.3531 L1807.1178,282.3531 L1807.1178,1920.0001 L112.9998,1920.0001 L112.9998,282.3531 C112.9998,188.9511 189.0088,112.9411 282.4118,112.9411 L282.4118,112.9411 L451.8228,112.9411 L451.8228,56.4711 C451.8228,25.2991 477.1228,9.99999997e-05 508.2938,9.99999997e-05 C539.4658,9.99999997e-05 564.7648,25.2991 564.7648,56.4711 L564.7648,56.4711 L564.7648,112.9411 L1355.3528,112.9411 L1355.3528,56.4711 C1355.3528,25.2991 1380.6518,9.99999997e-05 1411.8238,9.99999997e-05 Z M1694.1758,564.7051 L225.9418,564.7051 L225.9418,1807.0581 L1694.1758,1807.0581 L1694.1758,564.7051 Z M677.706,1242.353 L677.706,1581.177 L338.882,1581.177 L338.882,1242.353 L677.706,1242.353 Z M1129.471,1242.353 L1129.471,1581.177 L790.647,1581.177 L790.647,1242.353 L1129.471,1242.353 Z M1581.235,1242.353 L1581.235,1581.177 L1242.412,1581.177 L1242.412,1242.353 L1581.235,1242.353 Z M564.765,1355.294 L451.824,1355.294 L451.824,1468.235 L564.765,1468.235 L564.765,1355.294 Z M1016.529,1355.294 L903.588,1355.294 L903.588,1468.235 L1016.529,1468.235 L1016.529,1355.294 Z M1468.294,1355.294 L1355.353,1355.294 L1355.353,1468.235 L1468.294,1468.235 L1468.294,1355.294 Z M677.706,790.588 L677.706,1129.412 L338.882,1129.412 L338.882,790.588 L677.706,790.588 Z M1129.471,790.588 L1129.471,1129.412 L790.647,1129.412 L790.647,790.588 L1129.471,790.588 Z M1581.235,790.588 L1581.235,1129.412 L1242.412,1129.412 L1242.412,790.588 L1581.235,790.588 Z M564.765,903.53 L451.824,903.53 L451.824,1016.471 L564.765,1016.471 L564.765,903.53 Z M1016.529,903.53 L903.588,903.53 L903.588,1016.471 L1016.529,1016.471 L1016.529,903.53 Z M1468.294,903.53 L1355.353,903.53 L1355.353,1016.471 L1468.294,1016.471 L1468.294,903.53 Z M451.8228,225.8821 L282.4118,225.8821 C251.3528,225.8821 225.9418,251.1811 225.9418,282.3531 L225.9418,282.3531 L225.9418,451.7651 L1694.1758,451.7651 L1694.1758,282.3531 C1694.1758,251.1811 1668.7648,225.8821 1637.7058,225.8821 L1637.7058,225.8821 L1468.2938,225.8821 L1468.2938,282.3531 C1468.2938,313.5251 1442.9948,338.8241 1411.8238,338.8241 C1380.6518,338.8241 1355.3528,313.5251 1355.3528,282.3531 L1355.3528,282.3531 L1355.3528,225.8821 L564.7648,225.8821 L564.7648,282.3531 C564.7648,313.5251 539.4658,338.8241 508.2938,338.8241 C477.1228,338.8241 451.8228,313.5251 451.8228,282.3531 L451.8228,282.3531 L451.8228,225.8821 Z" fill-rule="evenodd" stroke="none" stroke-width="1"></path></g></svg></span></span></span></span></span></span></span></span></label><span id="Selectable__uzmkKykemeP6-description" class="bBhxr_dJgE">Type a date or use arrow keys to navigate date picker.</span><span><span data-position="Popover__u7CLL2V3zaKU"></span></span></span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span direction="row" wrap="no-wrap" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_oDLF bDzpk_cCxO bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_dfFp" style="padding: 0px 0.75rem 0px 0px;"><span class="cCAhm_bGBk cCAhm_ycrn"><label for="Select__u2uuCTCk2aOe" class="cWmNi_bGBk"><span class="cMIPy_bGBk"><span class="fxIji_bGBk fxIji_DpxJ fxIji_bWOh fxIji_NmrE fxIji_buDT fxIji_bBOa"><span class="bNerA_bGBk bNerA_NmrE bNerA_dBtH bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span class="fCrpb_bGBk fCrpb_egrg">From:</span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ bNerA_eFno" data-position-target="Popover__u6YpV3TZekba"><span class="qBMHb_cSXm"><span wrap="wrap" direction="row" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_busO bDzpk_fZWR bDzpk_dyGy bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_zczv dJCgj_dfFp"><span direction="row" wrap="no-wrap" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_busO bDzpk_fZWR bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_zczv dJCgj_dfFp"><input id="Select__u2uuCTCk2aOe" aria-haspopup="listbox" aria-expanded="false" aria-describedby="Selectable__uVTK3aakDVHY-description" data-testid="event-form-start-time" role="combobox" aria-autocomplete="both" autocomplete="off" class="qBMHb_cwos qBMHb_ycrn qBMHb_EMjX" placeholder="Start Time" type="text" value=""></span><span class="fOyUs_bGBk dJCgj_bGBk" style="padding: 0px 0.75rem 0px 0px;"><span class="cCAhm_dnnz"><svg name="IconArrowOpenDown" viewBox="0 0 1920 1920" rotate="0" style="width: 1em; height: 1em;" width="1em" height="1em" aria-hidden="true" role="presentation" focusable="false" class="dUOHu_bGBk dUOHu_drOs dUOHu_cRbP cGqzL_bGBk"><g role="presentation"><path d="M526.298905 0L434 92.1683552 1301.63582 959.934725 434 1827.57054 526.298905 1920 1486.23363 959.934725z" fill-rule="evenodd" stroke="none" stroke-width="1" transform="matrix(0 1 1 0 .153 -.153)"></path></g></svg></span></span></span></span></span></span></span></span></span></label><span id="Selectable__uVTK3aakDVHY-description" class="cCAhm_dJgE"></span><span><span data-position="Popover__u6YpV3TZekba"></span></span></span></span><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_dfFp" style="padding: 0px 0px 0px 0.75rem;"><span class="cCAhm_bGBk cCAhm_ycrn"><label for="Select__uK6bOkxP7VUV" class="cWmNi_bGBk"><span class="cMIPy_bGBk"><span class="fxIji_bGBk fxIji_DpxJ fxIji_bWOh fxIji_NmrE fxIji_buDT fxIji_bBOa"><span class="bNerA_bGBk bNerA_NmrE bNerA_dBtH bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span class="fCrpb_bGBk fCrpb_egrg">To:</span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ bNerA_eFno" data-position-target="Popover__u3yDpxxus2KK"><span class="qBMHb_cSXm"><span wrap="wrap" direction="row" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_busO bDzpk_fZWR bDzpk_dyGy bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_zczv dJCgj_dfFp"><span direction="row" wrap="no-wrap" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_busO bDzpk_fZWR bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_zczv dJCgj_dfFp"><input id="Select__uK6bOkxP7VUV" aria-haspopup="listbox" aria-expanded="false" aria-describedby="Selectable__unzYbmez3LHs-description" data-testid="event-form-end-time" role="combobox" aria-autocomplete="both" autocomplete="off" class="qBMHb_cwos qBMHb_ycrn qBMHb_EMjX" placeholder="End Time" type="text" value=""></span><span class="fOyUs_bGBk dJCgj_bGBk" style="padding: 0px 0.75rem 0px 0px;"><span class="cCAhm_dnnz"><svg name="IconArrowOpenDown" viewBox="0 0 1920 1920" rotate="0" style="width: 1em; height: 1em;" width="1em" height="1em" aria-hidden="true" role="presentation" focusable="false" class="dUOHu_bGBk dUOHu_drOs dUOHu_cRbP cGqzL_bGBk"><g role="presentation"><path d="M526.298905 0L434 92.1683552 1301.63582 959.934725 434 1827.57054 526.298905 1920 1486.23363 959.934725z" fill-rule="evenodd" stroke="none" stroke-width="1" transform="matrix(0 1 1 0 .153 -.153)"></path></g></svg></span></span></span></span></span></span></span></span></span></label><span id="Selectable__unzYbmez3LHs-description" class="cCAhm_dJgE"></span><span><span data-position="Popover__u3yDpxxus2KK"></span></span></span></span></span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ"><label for="TextInput__uzTzTuVyngTV" class="cWmNi_bGBk"><span class="cMIPy_bGBk"><span class="fxIji_bGBk fxIji_DpxJ fxIji_bWOh fxIji_NmrE fxIji_buDT fxIji_bBOa"><span class="bNerA_bGBk bNerA_NmrE bNerA_dBtH bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span class="fCrpb_bGBk fCrpb_egrg">Location:</span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ bNerA_eFno"><span class="qBMHb_cSXm"><input id="TextInput__uzTzTuVyngTV" class="qBMHb_cwos qBMHb_ycrn qBMHb_EMjX" placeholder="Input Event Location..." type="text" value=""></span></span></span></span></label></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span class="cCAhm_bGBk cCAhm_ycrn"><label for="Select__u7KO3gKx6n3n" class="cWmNi_bGBk"><span class="cMIPy_bGBk"><span class="fxIji_bGBk fxIji_DpxJ fxIji_bWOh fxIji_NmrE fxIji_buDT fxIji_bBOa"><span class="bNerA_bGBk bNerA_NmrE bNerA_dBtH bNerA_bBOa bNerA_buDT bNerA_DpxJ"><span class="fCrpb_bGBk fCrpb_egrg">Calendar:</span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ bNerA_eFno" data-position-target="Popover__uzu33kVejn27"><span class="qBMHb_cSXm"><span wrap="wrap" direction="row" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_busO bDzpk_fZWR bDzpk_dyGy bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_zczv dJCgj_dfFp"><span direction="row" wrap="no-wrap" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_busO bDzpk_fZWR bDzpk_qOas"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_zczv dJCgj_dfFp"><input id="Select__u7KO3gKx6n3n" aria-haspopup="listbox" aria-expanded="false" aria-describedby="Selectable__u7PgxaP6kYsg-description" data-testid="edit-calendar-event-form-context" role="button" autocomplete="off" readonly="" title="ianta@ualberta.ca" class="qBMHb_cwos qBMHb_ycrn qBMHb_EMjX" type="text" value="ianta@ualberta.ca"></span><span class="fOyUs_bGBk dJCgj_bGBk" style="padding: 0px 0.75rem 0px 0px;"><span class="cCAhm_dnnz"><svg name="IconArrowOpenDown" viewBox="0 0 1920 1920" rotate="0" style="width: 1em; height: 1em;" width="1em" height="1em" aria-hidden="true" role="presentation" focusable="false" class="dUOHu_bGBk dUOHu_drOs dUOHu_cRbP cGqzL_bGBk"><g role="presentation"><path d="M526.298905 0L434 92.1683552 1301.63582 959.934725 434 1827.57054 526.298905 1920 1486.23363 959.934725z" fill-rule="evenodd" stroke="none" stroke-width="1" transform="matrix(0 1 1 0 .153 -.153)"></path></g></svg></span></span></span></span></span></span></span></span></span></label><span id="Selectable__u7PgxaP6kYsg-description" class="cCAhm_dJgE">Use arrow keys to navigate options.</span><span><span data-position="Popover__uzu33kVejn27"></span></span></span></span><span class="bNerA_bGBk bNerA_NmrE bNerA_qfdC bNerA_bBOa bNerA_buDT bNerA_DpxJ bNerA_eFno"><span direction="row" wrap="no-wrap" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_crdn bDzpk_fZWR bDzpk_qOas" style="margin: 1.5rem 0px 0px;"><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_dfFp" style="padding: 0px 0.5rem;"><a data-testid="edit-calendar-event-more-options-button" cursor="pointer" href="/users/1/calendar_events/new" tabindex="0" class="fOyUs_bGBk fOyUs_fKyb fOyUs_cuDs fOyUs_cBHs fOyUs_eWbJ fOyUs_fmDy fOyUs_eeJl fOyUs_cBtr fOyUs_fuTR fOyUs_cnfU fQfxa_bGBk" style="margin: 0px; padding: 0px; border-radius: 0.25rem; border-width: 0px; width: auto; cursor: pointer;"><span class="fQfxa_caGd fQfxa_fKcQ fQfxa_buuG fQfxa_ImeN undefined fQfxa_dqAF"><span class="fQfxa_biBD">More Options</span></span></a></span><span class="fOyUs_bGBk dJCgj_bGBk dJCgj_dfFp" style="padding: 0px 0.125rem;"><button cursor="pointer" type="submit" class="fOyUs_bGBk fOyUs_fKyb fOyUs_cuDs fOyUs_cBHs fOyUs_eWbJ fOyUs_fmDy fOyUs_eeJl fOyUs_cBtr fOyUs_fuTR fOyUs_cnfU fQfxa_bGBk" style="margin: 0px; padding: 0px; border-radius: 0.25rem; border-width: 0px; width: auto; cursor: pointer;"><span class="fQfxa_caGd fQfxa_fKcQ fQfxa_eCSh fQfxa_ImeN undefined fQfxa_dqAF"><span class="fQfxa_biBD">Submit</span></span></button></span></span></span></span></span></span></span></span></span></fieldset></form></div>
  
  <div id="edit_appointment_group_form_holder" class="tab_holder clearfix"></div>
  
  
</div>
            """;
    private static final Document TEST_DOM = Jsoup.parse(TEST_HTML);
    private static final String LEAF_QUERY = "*:not(:has(*))";

    private static String attachPointXpath = "/html/body/div[6]/div[3]/div";
    private static String [] componentXpaths = {
            "/html/body/div[6]/div[3]/div/ul/li/a",
            "/html/body/div[6]/div[3]/div/div[1]/form/fieldset/span[1]/span/span/span/span/span/span[1]/span/label/span/span/span[1]/span/span/span/span/span/input",
            "/html/body/div[6]/div[3]/div/div[1]/form/"

    };

    @Test
    public void realCase(){
        Elements elements = TEST_DOM.select("*:not(:has(*))"); //Select leaf elements
        Coordinate attachPoint = ModelManager.materializeXpath(attachPointXpath);

        //For each leaf element materialize a structure leading up to the attach point and add its root as a child to the attach point
        List<Coordinate> leaves = elements.stream()
                /** Filter out 'head' elements because they are not actually part of the added component.
                 * JSoup created a <html><head></head><body></body><html> wrapper around HTML that it parses
                 * which lacks them. Thus we should ignore the empty 'head' leaf. The html and body tags are handled
                 * in {@link #computeComponentXpath(Element)}.
                 */
                .filter(element->!element.tagName().equals("head"))
                /**
                 * Compute internal component xpaths for the remaining HTML elements and fuse the
                 * component xpaths to the xpath of the attach point. Thus creating the xpath from the
                 * root of the document to the attached leaf.
                 */
                .map(element -> StateParser.fuseXpaths(attachPointXpath, Utils.computeComponentXpath(element)))
                .peek(s->log.info("leaf xpath: {}", s))
                /**
                 * Materialize the leaf xpaths into coordinates. Use the attach point as the stop condition.
                 * That is, we will only materialize the 'internal' part of the xpath.
                 */
                .map(xpath->ModelManager.materializeXpath(attachPointXpath, xpath))
                .peek(coordinate -> log.info("leaf coordinate: {}", coordinate))
                .collect(Collectors.toList());

        if(leaves.size() == 0){
            //Handle situationn with no leaves. I don't think this should be possible but...
            log.warn("No leaves in DOM_ADD");

        }

        ListIterator<Coordinate> it = leaves.listIterator();
        Graph cursorGraph = it.next().toGraph();
        while (it.hasNext()){
            Graph nextGraph = it.next().toGraph();
            cursorGraph = Graph.merge(cursorGraph, nextGraph);
            log.info("cursorgraph: {}", cursorGraph);
        }
        cursorGraph = Graph.merge(attachPoint.toGraph(), cursorGraph);
        log.info("cursorgraph w/attachpoint: {}", cursorGraph);

        Neo4JParser neo4j = new Neo4JParser("bolt://localhost", "neo4j","neo4j2");
        neo4j.persistGraph(cursorGraph, "whydoyousuck", "test");

//        Set<Coordinate> componentLeaves = cursorGraph.toCoordinate();
//        componentLeaves.forEach(leaf->{
//            leaf.getRoot().parent = attachPoint;
//        });
//
//        Graph finalGraph = componentLeaves.iterator().next().getRoot().toGraph();
//
//        log.info("final graph roots: {} finalgraph: {}", finalGraph.toCoordinate().size(), finalGraph.toString());
//
//
//        log.info("Attach point has {} children", attachPoint.numChildren());

        //TODO Bind the hydrated data
        //attachPoint.getRoot().toSet().forEach(coordinate -> coordinate.setData(dataMap.get(coordinate.xpath)));


    }

    /**
     * Test component attach operation.
     *
     * NOTE: Just attaching source and target leaves to the attach point is insufficient.
     * We need to be sure to attach the root of the leaf coordinates.
     *
     * Here's what this test does.
     *
     * It performs the component attachment in 2 different ways, and confirms that both
     * return the same result.
     *
     * Both ways begin with an attach point, let way 1 use altAttachPoint, and way2 use attachPoint.
     *
     * In way1 we first convert source and target into graphs, merge those graphs,
     * then retrieve the roots of the graphs and add all root coordinates as children to
     * altAttachPoint.
     *
     * In way2 we simply materialize source and target leaves then attach their roots to
     * attach point.
     *
     * IT IS CRITICAL to add the ROOTs of source and target as children to attach point
     * in order to perform correct component attachment.
     */
    @Test
    void componentAttachTest(){
        Coordinate altAttachPoint = ModelManager.materializeXpath(attachPointXpath);

        Coordinate attachPoint = ModelManager.materializeXpath(attachPointXpath);
        Coordinate source = ModelManager.materializeXpath(attachPointXpath, componentXpaths[0]);
        Coordinate target = ModelManager.materializeXpath(attachPointXpath, componentXpaths[2]);

        attachPoint.addChild(source.getRoot());
        attachPoint.addChild(target.getRoot());

        log.info("attachPointResult: {}", attachPoint.toGraph().toString());

        Graph sourceGraph = source.toGraph();
        Graph targetGraph = target.toGraph();

        Graph resultGraph = Graph.merge(sourceGraph, targetGraph);

        Set<Coordinate> componentLeaves = resultGraph.toCoordinate();
        componentLeaves.forEach(leaf->{
            log.info("leaf: {}", leaf.toString());
            altAttachPoint.addChild(leaf);
        });


        log.info("altAttachPointResult: {}", altAttachPoint.toGraph().toString());

        Graph difference = Graph.diff(altAttachPoint.toGraph(), attachPoint.toGraph());
        log.info("Difference graph: {}", difference.toString());

        assertEquals(altAttachPoint.toGraph(), attachPoint.toGraph());
    }

    @Test
    void mergeTest(){
        Coordinate source = makeSource();
        Coordinate target = makeTarget();

        log.info("source: {}", source);
        log.info("target: {}", target);

        Graph sourceGraph = source.toGraph();
        Graph targetGraph = target.toGraph();

        Graph resultGraph = Graph.merge(sourceGraph, targetGraph);


        Coordinate result = resultGraph.toCoordinate().iterator().next();
        log.info(result.toString());
        log.info(resultGraph.toString());

    }

    public Coordinate makeSource(){

        Coordinate a = new Coordinate();

        a.xpath = "/a";
        a.index = 0;
        a.parent = null;

        Coordinate b = new Coordinate();

        b.xpath ="/a/b";
        b.index = 0;
        b.parent = a;

        a.addChild(b);

        Coordinate c = new Coordinate();

        c.xpath = "/a/b/c";
        c.index = 0;
        c.parent = b;

        b.addChild(c);

        Coordinate e = new Coordinate();

        e.xpath = "/a/e";
        e.index = 0;
        e.parent = a;

        a.addChild(e);

        Coordinate g = new Coordinate();

        g.xpath = "/a/e/g";
        g.index = 0;
        g.parent = e;

        e.addChild(g);

        return a;
    }

    public Coordinate makeTarget(){

        Coordinate a = new Coordinate();

        a.xpath = "/a";
        a.index = 0;
        a.parent = null;

        Coordinate b = new Coordinate();

        b.xpath ="/a/b";
        b.index = 0;
        b.parent = a;

        a.addChild(b);

        Coordinate c = new Coordinate();

        c.xpath = "/a/b/c";
        c.index = 0;
        c.parent = b;

        b.addChild(c);

        Coordinate d = new Coordinate();

        d.xpath = "/a/b/d";
        d.index = 0;
        d.parent = b;

        b.addChild(d);


        return a;
    }

}
